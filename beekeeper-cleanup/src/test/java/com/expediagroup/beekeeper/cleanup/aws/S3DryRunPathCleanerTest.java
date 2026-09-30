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
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.LOCALSTACK_IMAGE;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.createEmptyBucket;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.createS3Client;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.doesBucketExist;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.doesObjectExist;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.putObject;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


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
  public static LocalStackContainer awsContainer = new LocalStackContainer(LOCALSTACK_IMAGE)
      .withServices(LocalStackContainer.Service.S3);

  @BeforeEach
  void setUp() {
    amazonS3 = createS3Client(awsContainer);
    createEmptyBucket(amazonS3, bucket);
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

  @Test
  void typicalForDirectory() {
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isTrue();
  }

  @Test
  void directoryWithTrailingSlash() {
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    String directoryPath = absolutePath + "/";
    housekeepingPath.setPath(directoryPath);
    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isTrue();
  }

  @Test
  void typicalForFile() {
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    String absoluteFilePath = "s3://" + bucket + "/" + key1;
    housekeepingPath.setPath(absoluteFilePath);
    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isTrue();
  }

  @Test
  void typicalWithSentinelFile() {
    String partition1Sentinel = "table/id1/partition_1_$folder$";
    putObject(amazonS3, bucket, partition1Sentinel, "");
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, partition1Sentinel)).isTrue();
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

    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, partition1Sentinel)).isTrue();
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

    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, partition1Sentinel)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, parentSentinelFile)).isTrue();
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
    s3DryRunPathCleaner.cleanupPath(housekeepingPath);

    assertThat(doesObjectExist(amazonS3, bucket, key1)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, key2)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, partition1Sentinel)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, parentSentinelFile)).isTrue();
    assertThat(doesObjectExist(amazonS3, bucket, tableSentinelFile)).isTrue();
  }

  @Test
  void pathDoesNotExist() {
    assertThatCode(() -> s3DryRunPathCleaner.cleanupPath(housekeepingPath)).doesNotThrowAnyException();
  }
}
