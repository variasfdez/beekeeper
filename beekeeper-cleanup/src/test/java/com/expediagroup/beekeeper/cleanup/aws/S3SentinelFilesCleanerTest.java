/**
 * Copyright (C) 2019-2026 Expedia, Inc.
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

import java.net.URI;
import java.util.List;

import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.localstack.LocalStackContainer;
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

@ExtendWith(MockitoExtension.class)
@Testcontainers
class S3SentinelFilesCleanerTest {

  private final String partition1Sentinel = "table/partition_1_$folder$";
  private final String partition1AbsolutePath = "s3://bucket/table/partition_1";
  private final String bucket = "bucket";
  private final String tableName = "table";

  private S3SentinelFilesCleaner s3SentinelFilesCleaner;
  private software.amazon.awssdk.services.s3.S3Client amazonS3;

  @Rule
  public static LocalStackContainer awsContainer = new LocalStackContainer(
      DockerImageName.parse("localstack/localstack:0.14.2")).withServices(S3);

  static {
    awsContainer.start();
  }

  public static String S3_ENDPOINT = awsContainer.getEndpointOverride(S3).toString();

  @BeforeEach
  void setUp() {
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
    S3Client s3Client = new S3Client(amazonS3, false);
    s3SentinelFilesCleaner = new S3SentinelFilesCleaner(s3Client);
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

  @Test
  void typical() {
    putObject(bucket, partition1Sentinel, "");
    s3SentinelFilesCleaner.deleteSentinelFiles(partition1AbsolutePath);
    assertThat(objectExists(bucket, partition1Sentinel)).isFalse();
  }

  @Test
  void sentinelFileWithSpaceInKey() {
    String partition1Sentinel = "table/ /partition_1_$folder$";
    String partition1AbsolutePath = "s3://bucket/table/ /partition_1";
    putObject(bucket, partition1Sentinel, "");
    s3SentinelFilesCleaner.deleteSentinelFiles(partition1AbsolutePath);
    assertThat(objectExists(bucket, partition1Sentinel)).isFalse();
  }

  @Test
  void invalidS3Path() {
    String invalidS3AbsolutePath = "table/partition_1";
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> s3SentinelFilesCleaner.deleteSentinelFiles(invalidS3AbsolutePath))
        .withMessage("Invalid S3 URI: no hostname: %s", invalidS3AbsolutePath);
  }

  @Test
  void moreThanOneSentinelFile() {
    String partition11Sentinel = "table/partition_11_$folder$";
    putObject(bucket, partition11Sentinel, "");
    putObject(bucket, partition1Sentinel, "");

    s3SentinelFilesCleaner.deleteSentinelFiles(partition1AbsolutePath);
    assertThat(objectExists(bucket, partition1Sentinel)).isFalse();
    assertThat(objectExists(bucket, partition11Sentinel)).isTrue();
  }

  @Test
  void nonEmptySentinelFile() {
    putObject(bucket, partition1Sentinel, "content");
    s3SentinelFilesCleaner.deleteSentinelFiles(partition1AbsolutePath);
    assertThat(objectExists(bucket, partition1Sentinel)).isTrue();
  }

  @Test
  void sentinelFileDoesntExist() {
    putObject(bucket, "table/partition_1", "content");
    assertThatCode(() -> s3SentinelFilesCleaner.deleteSentinelFiles(partition1AbsolutePath))
        .doesNotThrowAnyException();
  }

  @Test
  void sentinelFileForNonEmptyParent() {
    String partitionSentinel = "table/id1/partition_1_$folder$";
    String partitionParentSentinel = "table/id1_$folder$";
    String parentFile = "table/id1/randomFile";
    String partitionAbsolutePath = "s3://bucket/table/id1/partition_1";

    putObject(bucket, partitionSentinel, "");
    putObject(bucket, partitionParentSentinel, "");
    putObject(bucket, parentFile, "content");

    s3SentinelFilesCleaner.deleteSentinelFiles(partitionAbsolutePath);
    assertThat(objectExists(bucket, partitionSentinel)).isFalse();
    assertThat(objectExists(bucket, parentFile)).isTrue();
    assertThat(objectExists(bucket, partitionParentSentinel)).isTrue();
  }

  @Test
  void sentinelFileForEmptyParentPathDoesNotContainTableName() {
    String partitionSentinel = "randomLocation/id1/partition_1_$folder$";
    String partitionParentSentinel = "randomLocation/id1_$folder$";
    String partitionAbsolutePath = "s3://bucket/randomLocation/id1/partition_1";

    putObject(bucket, partitionSentinel, "");
    putObject(bucket, partitionParentSentinel, "");

    s3SentinelFilesCleaner.deleteSentinelFiles(partitionAbsolutePath);
    assertThat(objectExists(bucket, partitionSentinel)).isFalse();
    assertThat(objectExists(bucket, partitionParentSentinel)).isTrue();
  }

  @Test
  void sentinelFileForEmptyParentPathHasTableNamePrefix() {
    String tableTest = tableName + "Test";
    String partitionSentinel = tableTest + "/id1/partition_1_$folder$";
    String partitionParentSentinel = tableTest + "/id1_$folder$";
    String partitionAbsolutePath = "s3://bucket/" + tableTest + "/id1/partition_1";

    putObject(bucket, partitionSentinel, "");
    putObject(bucket, partitionParentSentinel, "");

    s3SentinelFilesCleaner.deleteSentinelFiles(partitionAbsolutePath);
    assertThat(objectExists(bucket, partitionSentinel)).isFalse();
    assertThat(objectExists(bucket, partitionParentSentinel)).isTrue();
  }

  @Test
  void sentinelFileForEmptyParentPathHasTableNameSuffix() {
    String testTable = "test" + tableName;
    String partitionSentinel = testTable + "/id1/partition_1_$folder$";
    String partitionParentSentinel = testTable + "/id1_$folder$";
    String partitionAbsolutePath = "s3://bucket/" + testTable + "/id1/partition_1";

    putObject(bucket, partitionSentinel, "");
    putObject(bucket, partitionParentSentinel, "");

    s3SentinelFilesCleaner.deleteSentinelFiles(partitionAbsolutePath);
    assertThat(objectExists(bucket, partitionSentinel)).isFalse();
    assertThat(objectExists(bucket, partitionParentSentinel)).isTrue();
  }
}
