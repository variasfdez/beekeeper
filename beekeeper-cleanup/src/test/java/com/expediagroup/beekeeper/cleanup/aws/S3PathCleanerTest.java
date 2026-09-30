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
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.LOCALSTACK_IMAGE;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.createEmptyBucket;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.createS3Client;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.doesBucketExist;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.doesObjectExist;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.listObjects;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.putObject;

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


import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Error;
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
  public static LocalStackContainer awsContainer = new LocalStackContainer(LOCALSTACK_IMAGE)
      .withServices(LocalStackContainer.Service.S3);

  static {
    awsContainer.start();
  }

  @BeforeEach
  void setUp() {
    amazonS3 = createS3Client(awsContainer);
    createEmptyBucket(amazonS3, bucket);
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

  @Test
  void typicalForDirectory() {
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isFalse();
    verify(bytesDeletedReporter).reportTaggable(content.getBytes().length * 2, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void directoryWithSpace() {
    String directoryPath = absolutePath + "/ /";
    housekeepingPath.setPath(directoryPath);
    putObject(amazonS3, bucket, keyRoot + "/ /file1", content);
    putObject(amazonS3, bucket, keyRoot + "/ /file2", content);

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(listObjects(amazonS3, bucket, "")).isEmpty();
  }

  @Test
  void directoryWithTrailingSlash() {
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    String directoryPath = absolutePath + "/";
    housekeepingPath.setPath(directoryPath);
    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isFalse();
    verify(bytesDeletedReporter).reportTaggable(content.getBytes().length * 2, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void typicalForFile() {
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    String absoluteFilePath = "s3://" + bucket + "/" + key1;
    housekeepingPath.setPath(absoluteFilePath);
    s3PathCleaner.cleanupPath(housekeepingPath);
    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isTrue();
    verify(bytesDeletedReporter).reportTaggable(content.getBytes().length, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void typicalWithSentinelFile() {
    putObject(amazonS3, bucket, partition1Sentinel, "");
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partition1Sentinel)).isFalse();
    verify(bytesDeletedReporter).reportTaggable(content.getBytes().length * 2, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void typicalWithAnotherFolderAndSentinelFile() {
    String partition10Sentinel = "table/id1/partition_10_$folder$";
    String partition10File = "table/id1/partition_10/data.file";
    assertThat(doesBucketExist(amazonS3, bucket)).isTrue();
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);
    putObject(amazonS3, bucket, partition1Sentinel, "");
    putObject(amazonS3, bucket, partition10File, content);
    putObject(amazonS3, bucket, partition10Sentinel, "");

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partition1Sentinel)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partition10File)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, partition10Sentinel)).isTrue();
  }

  @Test
  void typicalWithParentSentinelFiles() {
    String parentSentinelFile = "table/id1_$folder$";
    String tableSentinelFile = "table_$folder$";
    assertThat(doesBucketExist(amazonS3, bucket)).isTrue();
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);
    putObject(amazonS3, bucket, partition1Sentinel, "");
    putObject(amazonS3, bucket, parentSentinelFile, "");
    putObject(amazonS3, bucket, tableSentinelFile, "");

    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partition1Sentinel)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, parentSentinelFile)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, tableSentinelFile)).isTrue();
  }

  @Test
  void deleteTable() {
    String parentSentinelFile = "table/id1_$folder$";
    String tableSentinelFile = "table_$folder$";
    assertThat(doesBucketExist(amazonS3, bucket)).isTrue();
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);
    putObject(amazonS3, bucket, partition1Sentinel, "");
    putObject(amazonS3, bucket, parentSentinelFile, "");
    putObject(amazonS3, bucket, tableSentinelFile, "");

    String tableAbsolutePath = "s3://" + bucket + "/table";
    housekeepingPath.setPath(tableAbsolutePath);
    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partition1Sentinel)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, parentSentinelFile)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, tableSentinelFile)).isFalse();
    verify(bytesDeletedReporter).reportTaggable(content.getBytes().length * 2, housekeepingPath, FileSystemType.S3);
  }

  @Test
  void pathDoesNotExist() {
    assertThatCode(() -> s3PathCleaner.cleanupPath(housekeepingPath)).doesNotThrowAnyException();
  }

  @Test
  void sentinelFilesCleanerThrowsException() {
    S3SentinelFilesCleaner s3SentinelFilesCleaner = mock(S3SentinelFilesCleaner.class);
    doThrow(IllegalArgumentException.class).when(s3SentinelFilesCleaner).deleteSentinelFiles(absolutePath);

    putObject(amazonS3, bucket, key1, content);

    s3PathCleaner = new S3PathCleaner(s3Client, s3SentinelFilesCleaner, bytesDeletedReporter);
    assertThatCode(() -> s3PathCleaner.cleanupPath(housekeepingPath)).doesNotThrowAnyException();
    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
  }

  @Test
  void sentinelFileForTableDirectory() {
    String partitionSentinel = "table/id1/partition_1_$folder$";
    String partitionParentSentinel = "table/id1_$folder$";
    String tableSentinel = "table_$folder$";
    String partitionAbsolutePath = "s3://bucket/table/id1/partition_1";

    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, partitionSentinel, "");
    putObject(amazonS3, bucket, partitionParentSentinel, "");
    putObject(amazonS3, bucket, tableSentinel, "");

    housekeepingPath.setPath(partitionAbsolutePath);
    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partitionSentinel)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partitionParentSentinel)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, tableSentinel)).isTrue();
  }

  @Test
  void sentinelFileForEmptyParent() {
    String partitionSentinel = "table/id1/partition_1_$folder$";
    String partitionParentSentinel = "table/id1_$folder$";
    String partitionAbsolutePath = "s3://bucket/table/id1/partition_1";

    putObject(amazonS3, bucket, partitionSentinel, "");
    putObject(amazonS3, bucket, partitionParentSentinel, "");

    housekeepingPath.setPath(partitionAbsolutePath);
    s3PathCleaner.cleanupPath(housekeepingPath);
    assertThat(doesObjectExist(amazonS3, bucket, partitionSentinel)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partitionParentSentinel)).isFalse();
  }

  @Test
  void sentinelFilesForParentsAndPathWithTrailingSlash() {
    String partitionSentinel = "table/id1/partition_1_$folder$";
    String partitionParentSentinel = "table/id1_$folder$";
    String tableSentinel = "table_$folder$";
    String partitionAbsolutePath = "s3://bucket/table/id1/partition_1";

    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, partitionSentinel, "");
    putObject(amazonS3, bucket, partitionParentSentinel, "");
    putObject(amazonS3, bucket, tableSentinel, "");

    housekeepingPath.setPath(partitionAbsolutePath + "/");
    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partitionSentinel)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, partitionParentSentinel)).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, tableSentinel)).isTrue();
  }

  @Test
  void noBytesDeletedMetricWhenFileDeletionFails() {
    S3Client mockS3Client = mock(S3Client.class);
    s3PathCleaner = new S3PathCleaner(mockS3Client, s3SentinelFilesCleaner, bytesDeletedReporter);
    when(mockS3Client.doesObjectExist(bucket, key1)).thenReturn(true);
    when(mockS3Client.getObjectMetadata(bucket, key1))
        .thenReturn(HeadObjectResponse.builder().contentLength(10L).build());
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
  void typicalForDirectoryWithPercentEncodedCharacters() {
    String encodedPartition = "table/id1/hour=2020-01-01 00%3A00%3A00";
    String decodedPartitionFile = "table/id1/hour=2020-01-01 00:00:00/file1";
    putObject(amazonS3, bucket, encodedPartition + "/file1", content);
    putObject(amazonS3, bucket, encodedPartition + "/file2", content);
    putObject(amazonS3, bucket, decodedPartitionFile, content);

    housekeepingPath.setPath("s3://" + bucket + "/" + encodedPartition);
    s3PathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, encodedPartition + "/file1")).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, encodedPartition + "/file2")).isFalse();
    assertThat(doesObjectExist(amazonS3, bucket, decodedPartitionFile)).isTrue();
  }

  @Test
  void extractingURIFails() {
    String path = "not a real path";
    housekeepingPath.setPath(path);
    assertThatExceptionOfType(BeekeeperException.class)
        .isThrownBy(() -> s3PathCleaner.cleanupPath(housekeepingPath))
        .withMessage(format("'%s' is not an S3 path.", path));
  }

  private void mockOneOutOfTwoObjectsDeleted(software.amazon.awssdk.services.s3.S3Client mockAmazonS3) {
    S3Object s3Object = S3Object.builder().key(key1).size(100L).build();
    S3Object s3Object2 = S3Object.builder().key(key2).size(50L).build();
    when(mockAmazonS3.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
    when(mockAmazonS3.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenReturn(ListObjectsV2Response.builder().contents(s3Object, s3Object2).isTruncated(false).build());
    when(mockAmazonS3.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder()
            .deleted(DeletedObject.builder().key(key1).build())
            .errors(S3Error.builder().key(key2).code("AccessDenied").message("Access Denied").build())
            .build());
  }
}
