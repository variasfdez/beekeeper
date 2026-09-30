/**
 * Copyright (C) 2019-2024 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.expediagroup.beekeeper.cleanup.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import com.expediagroup.beekeeper.cleanup.monitoring.BytesDeletedReporter;
import com.expediagroup.beekeeper.core.model.HousekeepingPath;
import com.expediagroup.beekeeper.core.model.PeriodDuration;

@ExtendWith(MockitoExtension.class)
@Testcontainers
class S3DryRunPathCleanerTest {

  private final String content = "Some content";
  private final String bucket = "bucket";
  private final String keyRoot = "table/id1/partition_1";
  private final String key1 = "table/id1/partition_1/file1";
  private final String key2 = "table/id1/partition_1/file2";
  private final String partition1Sentinel = "table/id1/partition_1_$folder$";
  private final String absolutePath = "s3://" + bucket + "/" + keyRoot;
  private final String tableName = "table";
  private final String databaseName = "database";

  private HousekeepingPath housekeepingPath;
  private software.amazon.awssdk.services.s3.S3Client amazonS3;
  private @Mock BytesDeletedReporter bytesDeletedReporter;

  private boolean dryRunEnabled = true;

  private S3PathCleaner s3DryRunPathCleaner;

  @Container
  public static LocalStackContainer awsContainer = new LocalStackContainer(
      DockerImageName.parse("localstack/localstack:0.14.2")).withServices(S3);

  @BeforeEach
  void setUp() {
    String S3_ENDPOINT = awsContainer.getEndpointOverride(S3).toString();
    amazonS3 =
        software.amazon.awssdk.services.s3.S3Client.builder()
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("accesskey", "secretkey")))
            .endpointOverride(URI.create(S3_ENDPOINT))
            .region(Region.of("region"))
            .forcePathStyle(true)
            // LocalStack 0.14.2 does not decode the aws-chunked trailer-checksum uploads that the
            // v2 SDK sends by default, which would corrupt object sizes
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .build();
    createBucket(bucket);
    listObjects(bucket).forEach(object -> deleteObject(bucket, object.key()));
    S3Client s3Client = new S3Client(amazonS3, dryRunEnabled);
    s3DryRunPathCleaner = new S3PathCleaner(s3Client, new S3SentinelFilesCleaner(s3Client), bytesDeletedReporter);
    housekeepingPath = HousekeepingPath
        .builder()
        .path(absolutePath)
        .tableName(tableName)
        .databaseName(databaseName)
        .creationTimestamp(LocalDateTime.now())
        .cleanupDelay(PeriodDuration.of(Duration.ofDays(1)))
        .build();
  }

  private void createBucket(String bucket) {
    if (amazonS3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) {
      amazonS3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    }
  }

  private void putObject(String bucket, String key, String content) {
    amazonS3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).build(),
        RequestBody.fromString(content));
  }

  private void deleteObject(String bucket, String key) {
    amazonS3.deleteObject(builder -> builder.bucket(bucket).key(key));
  }

  private List<S3Object> listObjects(String bucket) {
    return amazonS3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents();
  }

  private boolean objectExists(String bucket, String key) {
    try {
      amazonS3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  private boolean bucketExists(String bucket) {
    return amazonS3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(bucket));
  }

  @Test
  void typicalForDirectory() {
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isTrue();
    assertThat(objectExists(bucket, key2)).isTrue();
  }

  @Test
  void directoryWithTrailingSlash() {
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    String directoryPath = absolutePath + "/";
    housekeepingPath.setPath(directoryPath);
    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isTrue();
    assertThat(objectExists(bucket, key2)).isTrue();
  }

  @Test
  void typicalForFile() {
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    String absoluteFilePath = "s3://" + bucket + "/" + key1;
    housekeepingPath.setPath(absoluteFilePath);
    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isTrue();
    assertThat(objectExists(bucket, key2)).isTrue();
  }

  @Test
  void typicalWithSentinelFile() {
    String partition1Sentinel = "table/id1/partition_1_$folder$";
    putObject(bucket, partition1Sentinel, "");
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isTrue();
    assertThat(objectExists(bucket, key2)).isTrue();
    assertThat(objectExists(bucket, partition1Sentinel)).isTrue();
  }

  @Test
  void typicalWithAnotherFolderAndSentinelFile() {
    String partition10Sentinel = "table/id1/partition_10_$folder$";
    String partition10File = "table/id1/partition_10/data.file";
    assertThat(bucketExists(bucket)).isTrue();
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);
    putObject(bucket, partition1Sentinel, "");
    putObject(bucket, partition10File, content);
    putObject(bucket, partition10Sentinel, "");

    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isTrue();
    assertThat(objectExists(bucket, key2)).isTrue();
    assertThat(objectExists(bucket, partition1Sentinel)).isTrue();
    assertThat(objectExists(bucket, partition10File)).isTrue();
    assertThat(objectExists(bucket, partition10Sentinel)).isTrue();
  }

  @Test
  void typicalWithParentSentinelFiles() {
    String parentSentinelFile = "table/id1_$folder$";
    String tableSentinelFile = "table_$folder$";
    assertThat(bucketExists(bucket)).isTrue();
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);
    putObject(bucket, partition1Sentinel, "");
    putObject(bucket, parentSentinelFile, "");
    putObject(bucket, tableSentinelFile, "");

    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isTrue();
    assertThat(objectExists(bucket, key2)).isTrue();
    assertThat(objectExists(bucket, partition1Sentinel)).isTrue();
    assertThat(objectExists(bucket, parentSentinelFile)).isTrue();
    assertThat(objectExists(bucket, tableSentinelFile)).isTrue();
  }

  @Test
  void deleteTable() {
    String parentSentinelFile = "table/id1_$folder$";
    String tableSentinelFile = "table_$folder$";
    assertThat(bucketExists(bucket)).isTrue();
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);
    putObject(bucket, partition1Sentinel, "");
    putObject(bucket, parentSentinelFile, "");
    putObject(bucket, tableSentinelFile, "");

    String tableAbsolutePath = "s3://" + bucket + "/table";
    housekeepingPath.setPath(tableAbsolutePath);
    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isTrue();
    assertThat(objectExists(bucket, key2)).isTrue();
    assertThat(objectExists(bucket, partition1Sentinel)).isTrue();
    assertThat(objectExists(bucket, parentSentinelFile)).isTrue();
    assertThat(objectExists(bucket, tableSentinelFile)).isTrue();
  }

  @Test
  void pathDoesNotExist() {
    assertThatCode(() -> s3DryRunPathCleaner.cleanupPath(housekeepingPath)).doesNotThrowAnyException();
  }
}
