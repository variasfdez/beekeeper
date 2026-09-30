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

import static java.lang.String.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import com.expediagroup.beekeeper.cleanup.monitoring.BytesDeletedReporter;
import com.expediagroup.beekeeper.core.config.FileSystemType;
import com.expediagroup.beekeeper.core.error.BeekeeperException;
import com.expediagroup.beekeeper.core.model.HousekeepingPath;
import com.expediagroup.beekeeper.core.model.PeriodDuration;

@ExtendWith(MockitoExtension.class)
@Testcontainers
class S3PathCleanerTest {

  private final String content = "Some content";
  private final String bucket = "bucket";
  private final String keyRoot = "table/id1/partition_1";
  private final String keyRootAsDirectory = keyRoot + "/";
  private final String key1 = "table/id1/partition_1/file1";
  private final String key2 = "table/id1/partition_1/file2";
  private final String partition1Sentinel = "table/id1/partition_1_$folder$";
  private final String absolutePath = "s3://" + bucket + "/" + keyRoot;

  private HousekeepingPath housekeepingPath;
  private software.amazon.awssdk.services.s3.S3Client amazonS3;
  private S3Client s3Client;
  private S3SentinelFilesCleaner s3SentinelFilesCleaner;
  private @Mock BytesDeletedReporter bytesDeletedReporter;

  private S3PathCleaner s3PathCleaner;

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
    boolean dryRunEnabled = false;
    s3Client = new S3Client(amazonS3, dryRunEnabled);
    s3SentinelFilesCleaner = new S3SentinelFilesCleaner(s3Client);
    s3PathCleaner = new S3PathCleaner(s3Client, s3SentinelFilesCleaner, bytesDeletedReporter);
    String tableName = "table";
    String databaseName = "database";
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

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, key2)).isFalse();
    verify(bytesDeletedReporter)
        .reportTaggable(content.getBytes().length * 2, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void directoryWithSpace() {
    String directoryPath = absolutePath + "/ /";
    housekeepingPath.setPath(directoryPath);
    putObject(bucket, keyRoot + "/ /file1", content);
    putObject(bucket, keyRoot + "/ /file2", content);

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(listObjects(bucket)).isEmpty();
  }

  @Test
  void directoryWithTrailingSlash() {
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    String directoryPath = absolutePath + "/";
    housekeepingPath.setPath(directoryPath);
    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, key2)).isFalse();
    verify(bytesDeletedReporter)
        .reportTaggable(content.getBytes().length * 2, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void typicalForFile() {
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    String absoluteFilePath = "s3://" + bucket + "/" + key1;
    housekeepingPath.setPath(absoluteFilePath);
    s3PathCleaner.cleanupPath(housekeepingPath);
    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, key2)).isTrue();
    verify(bytesDeletedReporter)
        .reportTaggable(content.getBytes().length, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void typicalWithSentinelFile() {
    putObject(bucket, partition1Sentinel, "");
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, key2)).isFalse();
    assertThat(objectExists(bucket, partition1Sentinel)).isFalse();
    verify(bytesDeletedReporter)
        .reportTaggable(content.getBytes().length * 2, housekeepingPath, FileSystemType.S3);
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

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, key2)).isFalse();
    assertThat(objectExists(bucket, partition1Sentinel)).isFalse();
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

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, key2)).isFalse();
    assertThat(objectExists(bucket, partition1Sentinel)).isFalse();
    assertThat(objectExists(bucket, parentSentinelFile)).isFalse();
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
    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, key2)).isFalse();
    assertThat(objectExists(bucket, partition1Sentinel)).isFalse();
    assertThat(objectExists(bucket, parentSentinelFile)).isFalse();
    assertThat(objectExists(bucket, tableSentinelFile)).isFalse();
    verify(bytesDeletedReporter)
        .reportTaggable(content.getBytes().length * 2, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void pathDoesNotExist() {
    assertThatCode(() -> s3PathCleaner.cleanupPath(housekeepingPath)).doesNotThrowAnyException();
  }

  @Test
  void sentinelFilesCleanerThrowsException() {
    S3SentinelFilesCleaner s3SentinelFilesCleaner = mock(S3SentinelFilesCleaner.class);
    doThrow(IllegalArgumentException.class).when(s3SentinelFilesCleaner).deleteSentinelFiles(absolutePath);

    putObject(bucket, key1, content);

    s3PathCleaner = new S3PathCleaner(s3Client, s3SentinelFilesCleaner, bytesDeletedReporter);
    assertThatCode(() -> s3PathCleaner.cleanupPath(housekeepingPath)).doesNotThrowAnyException();
    assertThat(objectExists(bucket, key1)).isFalse();
  }

  @Test
  void sentinelFileForTableDirectory() {
    String partitionSentinel = "table/id1/partition_1_$folder$";
    String partitionParentSentinel = "table/id1_$folder$";
    String tableSentinel = "table_$folder$";
    String partitionAbsolutePath = "s3://bucket/table/id1/partition_1";

    putObject(bucket, key1, content);
    putObject(bucket, partitionSentinel, "");
    putObject(bucket, partitionParentSentinel, "");
    putObject(bucket, tableSentinel, "");

    housekeepingPath.setPath(partitionAbsolutePath);
    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, partitionSentinel)).isFalse();
    assertThat(objectExists(bucket, partitionParentSentinel)).isFalse();
    assertThat(objectExists(bucket, tableSentinel)).isTrue();
  }

  @Test
  void sentinelFileForEmptyParent() {
    String partitionSentinel = "table/id1/partition_1_$folder$";
    String partitionParentSentinel = "table/id1_$folder$";
    String partitionAbsolutePath = "s3://bucket/table/id1/partition_1";

    putObject(bucket, partitionSentinel, "");
    putObject(bucket, partitionParentSentinel, "");

    housekeepingPath.setPath(partitionAbsolutePath);
    s3PathCleaner.cleanupPath(housekeepingPath);
    assertThat(objectExists(bucket, partitionSentinel)).isFalse();
    assertThat(objectExists(bucket, partitionParentSentinel)).isFalse();
  }

  @Test
  void sentinelFilesForParentsAndPathWithTrailingSlash() {
    String partitionSentinel = "table/id1/partition_1_$folder$";
    String partitionParentSentinel = "table/id1_$folder$";
    String tableSentinel = "table_$folder$";
    String partitionAbsolutePath = "s3://bucket/table/id1/partition_1";

    putObject(bucket, key1, content);
    putObject(bucket, partitionSentinel, "");
    putObject(bucket, partitionParentSentinel, "");
    putObject(bucket, tableSentinel, "");

    housekeepingPath.setPath(partitionAbsolutePath + "/");
    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, partitionSentinel)).isFalse();
    assertThat(objectExists(bucket, partitionParentSentinel)).isFalse();
    assertThat(objectExists(bucket, tableSentinel)).isTrue();
  }

  @Test
  void noBytesDeletedMetricWhenFileDeletionFails() {
    S3Client mockS3Client = mock(S3Client.class);
    s3PathCleaner = new S3PathCleaner(mockS3Client, s3SentinelFilesCleaner, bytesDeletedReporter);
    when(mockS3Client.doesObjectExist(bucket, key1)).thenReturn(true);
    HeadObjectResponse objectMetadata = HeadObjectResponse.builder().contentLength(10L).build();
    when(mockS3Client.getObjectMetadata(bucket, key1)).thenReturn(objectMetadata);
    doThrow(S3Exception.class).when(mockS3Client).deleteObject(bucket, key1);

    housekeepingPath.setPath(absolutePath + "/file1");
    assertThatExceptionOfType(S3Exception.class)
        .isThrownBy(() -> s3PathCleaner.cleanupPath(housekeepingPath));
    verifyNoInteractions(bytesDeletedReporter);
  }

  @Test
  void noBytesDeletedMetricWhenDirectoryDeletionFails() {
    S3Client mockS3Client = mock(S3Client.class);
    s3PathCleaner = new S3PathCleaner(mockS3Client, s3SentinelFilesCleaner, bytesDeletedReporter);
    doThrow(S3Exception.class).when(mockS3Client).listObjects(bucket, keyRootAsDirectory);

    assertThatExceptionOfType(S3Exception.class)
        .isThrownBy(() -> s3PathCleaner.cleanupPath(housekeepingPath));
    verifyNoInteractions(bytesDeletedReporter);
  }

  @Test
  void reportBytesDeletedWhenDirectoryDeletionPartiallyFails() {
    software.amazon.awssdk.services.s3.S3Client mockAmazonS3 =
        mock(software.amazon.awssdk.services.s3.S3Client.class);
    S3Client mockS3Client = new S3Client(mockAmazonS3, false);
    mockOneOutOfTwoObjectsDeleted(mockAmazonS3);
    s3PathCleaner = new S3PathCleaner(mockS3Client, s3SentinelFilesCleaner, bytesDeletedReporter);
    assertThatExceptionOfType(BeekeeperException.class)
        .isThrownBy(() -> s3PathCleaner.cleanupPath(housekeepingPath))
        .withMessage(format("Not all files could be deleted at path \"%s/%s\"; deleted 1/2 objects. "
            + "Objects not deleted: 'table/id1/partition_1/file2'.", bucket, keyRootAsDirectory));
    verify(bytesDeletedReporter).reportTaggable(100L, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void extractingURIFails() {
    String path = "not a real path";
    housekeepingPath.setPath(path);
    assertThatExceptionOfType(BeekeeperException.class)
        .isThrownBy(() -> s3PathCleaner.cleanupPath(housekeepingPath))
        .withMessage(format("'%s' is not an S3 path.", path));
  }

  private void mockOneOutOfTwoObjectsDeleted(
      software.amazon.awssdk.services.s3.S3Client mockAmazonS3) {
    // the path is a directory: a HEAD on it returns 404
    when(mockAmazonS3.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).build());
    S3Object s3ObjectSummary = S3Object.builder().key(key1).size(100L).build();
    S3Object s3ObjectSummary2 = S3Object.builder().key(key2).size(50L).build();
    ListObjectsV2Response listObjectsV2Response =
        ListObjectsV2Response.builder()
            .contents(List.of(s3ObjectSummary, s3ObjectSummary2))
            .isTruncated(false)
            .build();
    when(mockAmazonS3.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenReturn(listObjectsV2Response);
    DeletedObject deletedObject = DeletedObject.builder().key(key1).build();
    when(mockAmazonS3.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder().deleted(deletedObject).build());
  }
}
