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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.LOCALSTACK_IMAGE;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.createEmptyBucket;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.createS3Client;
import static com.expediagroup.beekeeper.cleanup.aws.S3TestUtils.putObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

@Testcontainers
class S3ClientTest {

  private final String content = "content";
  private final String keyRoot = "table/partition_1";
  private final String key1 = "table/partition_1/file1";
  private final String key2 = "table/partition_1/file2";
  private final String bucket = "bucket";

  private S3Client s3Client;
  private S3Client s3ClientDryRun;
  private software.amazon.awssdk.services.s3.S3Client amazonS3;

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
    assertThat(S3TestUtils.listObjects(amazonS3, bucket, "")).isEmpty();
    s3Client = new S3Client(amazonS3, false);
    s3ClientDryRun = new S3Client(amazonS3, true);
  }

  @Test
  void deleteObject() {
    putObject(amazonS3, bucket, key1, content);
    s3Client.deleteObject(bucket, key1);
    assertThat(S3TestUtils.doesObjectExist(amazonS3, bucket, key1)).isFalse();
  }

  @Test
  void deleteObjectWithSpace() {
    String spacedKey = keyRoot + "/ /file";
    putObject(amazonS3, bucket, spacedKey, content);
    s3Client.deleteObject(bucket, spacedKey);
    assertThat(S3TestUtils.doesObjectExist(amazonS3, bucket, spacedKey)).isFalse();
  }

  @Test
  void deleteObjectsWithSpace() {
    String spacedKey1 = keyRoot + "/ /file1";
    String spacedKey2 = keyRoot + "/ /file2";
    putObject(amazonS3, bucket, spacedKey1, content);
    putObject(amazonS3, bucket, spacedKey2, content);
    s3Client.deleteObjects(bucket, List.of(spacedKey1, spacedKey2));
    assertThat(S3TestUtils.doesObjectExist(amazonS3, bucket, spacedKey1)).isFalse();
    assertThat(S3TestUtils.doesObjectExist(amazonS3, bucket, spacedKey2)).isFalse();
  }

  @Test
  void deleteObjectDryRun() {
    putObject(amazonS3, bucket, key1, content);
    s3ClientDryRun.deleteObject(bucket, key1);
    assertThat(S3TestUtils.doesObjectExist(amazonS3, bucket, key1)).isTrue();
  }

  @Test
  void listObjects() {
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    List<S3Object> result = s3Client.listObjects(bucket, keyRoot);

    assertThat(result.size()).isEqualTo(2);
    assertThat(result.get(0).key()).isEqualTo(key1);
    assertThat(result.get(0).size()).isEqualTo(content.length());
    assertThat(result.get(1).key()).isEqualTo(key2);
    assertThat(result.get(1).size()).isEqualTo(content.length());
  }

  @Test
  void listObjectsWithSpace() {
    String spacedKey1 = keyRoot + "/ /file1";
    String spacedKey2 = keyRoot + "/ /file2";
    putObject(amazonS3, bucket, spacedKey1, content);
    putObject(amazonS3, bucket, spacedKey2, content);

    List<S3Object> result = s3Client.listObjects(bucket, keyRoot);

    assertThat(result.size()).isEqualTo(2);
    assertThat(result.get(0).key()).isEqualTo(spacedKey1);
    assertThat(result.get(1).key()).isEqualTo(spacedKey2);
  }

  @Test
  void listObjectsWithSpaceInSearch() {
    String spacedKeyRoot = keyRoot + "/ /";
    String spacedKey1 = spacedKeyRoot + "file1";
    String spacedKey2 = spacedKeyRoot + "file2";
    putObject(amazonS3, bucket, spacedKey1, content);
    putObject(amazonS3, bucket, spacedKey2, content);

    List<S3Object> result = s3Client.listObjects(bucket, spacedKeyRoot);

    assertThat(result.size()).isEqualTo(2);
    assertThat(result.get(0).key()).isEqualTo(spacedKey1);
    assertThat(result.get(1).key()).isEqualTo(spacedKey2);
  }

  @Test
  void listObjectsReturnsRawKeysWithSpecialCharacters() {
    String encodedKey = keyRoot + "/hour=2020-01-01 00%3A00%3A00/file";
    String plusKey = keyRoot + "/a+b/file";
    String unicodeKey = keyRoot + "/\u00e9t\u00e9/file";
    putObject(amazonS3, bucket, encodedKey, content);
    putObject(amazonS3, bucket, plusKey, content);
    putObject(amazonS3, bucket, unicodeKey, content);

    List<String> result = s3Client.listObjects(bucket, keyRoot)
        .stream()
        .map(S3Object::key)
        .collect(Collectors.toList());

    assertThat(result).containsExactlyInAnyOrder(encodedKey, plusKey, unicodeKey);
    assertThat(s3Client.deleteObjects(bucket, result)).containsExactlyInAnyOrderElementsOf(result);
    assertThat(S3TestUtils.listObjects(amazonS3, bucket, keyRoot)).isEmpty();
  }

  @Test
  void listObjectsWithXmlInvalidControlCharacterAcrossPages() {
    List<String> keys = new ArrayList<>();
    for (int i = 1; i <= 1100; i++) {
      keys.add(keyRoot + "/file" + i);
    }
    List<String> controlCharacterKeys =
        List.of(
            keyRoot + "/a\u0001file",
            keyRoot + "/z\u0001file",
            keyRoot + "/z\u0001%3A%2B+file");
    keys.addAll(controlCharacterKeys);
    keys.parallelStream().forEach(key -> putObject(amazonS3, bucket, key, content));
    try {
      List<String> result =
          s3Client.listObjects(bucket, keyRoot).stream()
              .map(S3Object::key)
              .collect(Collectors.toList());

      assertThat(result).containsExactlyInAnyOrderElementsOf(keys);
      assertThatExceptionOfType(S3Exception.class)
          .isThrownBy(() -> s3Client.deleteObjects(bucket, controlCharacterKeys));
    } finally {
      controlCharacterKeys.forEach(key -> amazonS3.deleteObject(b -> b.bucket(bucket).key(key)));
    }
  }

  @Test
  void listBatchObjects() {
    int s3BatchSize = 1000;
    int extraKeys = 100;
    List<String> keys = new ArrayList<>();
    for (int i = 1; i <= s3BatchSize + extraKeys; i++) {
      keys.add(keyRoot + "/file" + i);
    }
    keys.parallelStream().forEach(key -> putObject(amazonS3, bucket, key, content));

    List<S3Object> result = s3Client.listObjects(bucket, keyRoot);

    assertThat(result.size()).isEqualTo(s3BatchSize + extraKeys);
  }

  @Test
  void deleteObjectsInDirectory() {
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    List<String> result = s3Client.deleteObjects(bucket, List.of(key1, key2));

    assertThat(result.size()).isEqualTo(2);
    assertThat(result).contains(key1);
    assertThat(result).contains(key2);
    assertThat(S3TestUtils.doesObjectExist(amazonS3, bucket, key1)).isFalse();
    assertThat(S3TestUtils.doesObjectExist(amazonS3, bucket, key2)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = { 500, 1000, 1500 })
  void splitDeleteObjectsInDirectory(final int totalObjects) {
    ArrayList<String> keys = new ArrayList<>();
    for (int i = 1; i <= totalObjects; i++) {
      var key = keyRoot + "/file" + i;
      keys.add(key);
    }
    keys.parallelStream().forEach(key -> putObject(amazonS3, bucket, key, content));

    List<String> result = s3Client.deleteObjects(bucket, keys);
    assertThat(result.size()).isEqualTo(totalObjects);

    int numberOfObjectsLeft = S3TestUtils.listObjects(amazonS3, bucket, keyRoot).size();
    assertThat(numberOfObjectsLeft).isEqualTo(0);

    assertThat(keys).isEqualTo(result);
  }

  @Test
  void deleteObjectsInDirectoryDryRun() {
    putObject(amazonS3, bucket, key1, content);
    putObject(amazonS3, bucket, key2, content);

    List<String> result = s3ClientDryRun.deleteObjects(bucket, List.of(key1, key2));

    assertThat(result.size()).isEqualTo(2);
    assertThat(result).contains(key1);
    assertThat(result).contains(key2);
    assertThat(S3TestUtils.doesObjectExist(amazonS3, bucket, key1)).isTrue();
    assertThat(S3TestUtils.doesObjectExist(amazonS3, bucket, key2)).isTrue();
  }

  @Test
  void deleteObjectsEmptyRequest() {
    software.amazon.awssdk.services.s3.S3Client amazonS3 =
        Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class);
    S3Client s3Client = new S3Client(amazonS3, false);

    List<String> result = s3Client.deleteObjects(bucket, Collections.emptyList());

    assertThat(result.size()).isEqualTo(0);
    verifyNoMoreInteractions(amazonS3);
  }

  @Test
  void deleteObjectsPartialFailureReturnsOnlyDeletedKeysAndStopsSendingChunks() {
    software.amazon.awssdk.services.s3.S3Client amazonS3 =
        Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class);
    S3Client s3Client = new S3Client(amazonS3, false);
    List<String> keys = new ArrayList<>();
    for (int i = 1; i <= 1500; i++) {
      keys.add(keyRoot + "/file" + i);
    }
    List<DeletedObject> deleted = keys.subList(1, 1000)
        .stream()
        .map(key -> DeletedObject.builder().key(key).build())
        .collect(Collectors.toList());
    when(amazonS3.deleteObjects(any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder()
            .deleted(deleted)
            .errors(S3Error.builder().key(keys.get(0)).code("AccessDenied").message("Access Denied").build())
            .build());

    List<String> result = s3Client.deleteObjects(bucket, keys);

    assertThat(result).containsExactlyElementsOf(keys.subList(1, 1000));
    verify(amazonS3, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
  }

  @Test
  void doesObjectExistForFile() {
    putObject(amazonS3, bucket, key2, content);
    boolean result = s3Client.doesObjectExist(bucket, key2);
    assertThat(result).isTrue();
  }

  @Test
  void doesObjectExistForFileWithSpaceInKey() {
    String spacedKey = key2 + "/ /file";
    putObject(amazonS3, bucket, spacedKey, content);
    boolean result = s3Client.doesObjectExist(bucket, spacedKey);
    assertThat(result).isTrue();
  }

  @Test
  void doesObjectExistForDirectory() {
    putObject(amazonS3, bucket, key2, content);
    boolean result = s3Client.doesObjectExist(bucket, keyRoot);
    assertThat(result).isFalse();
  }

  @Test
  void doesObjectExistForDirectoryWithTrailingSlash() {
    putObject(amazonS3, bucket, key2, content);
    boolean result = s3Client.doesObjectExist(bucket, keyRoot + "/");
    assertThat(result).isFalse();
  }

  @Test
  void doesObjectExistIsFalseForNoSuchKeyException() {
    software.amazon.awssdk.services.s3.S3Client amazonS3 =
        Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class);
    when(amazonS3.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
    assertThat(new S3Client(amazonS3, false).doesObjectExist(bucket, key1)).isFalse();
  }

  @Test
  void doesObjectExistIsFalseFor404S3Exception() {
    software.amazon.awssdk.services.s3.S3Client amazonS3 =
        Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class);
    when(amazonS3.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).build());
    assertThat(new S3Client(amazonS3, false).doesObjectExist(bucket, key1)).isFalse();
  }

  @Test
  void doesObjectExistRethrowsNon404S3Exception() {
    software.amazon.awssdk.services.s3.S3Client amazonS3 =
        Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class);
    S3Exception forbidden = (S3Exception) S3Exception.builder().statusCode(403).build();
    when(amazonS3.headObject(any(HeadObjectRequest.class))).thenThrow(forbidden);
    assertThatExceptionOfType(S3Exception.class)
        .isThrownBy(() -> new S3Client(amazonS3, false).doesObjectExist(bucket, key1))
        .isSameAs(forbidden);
  }

  @Test
  void getObjectSize() {
    putObject(amazonS3, bucket, key2, content);
    HeadObjectResponse result = s3Client.getObjectMetadata(bucket, key2);
    assertThat(result.contentLength()).isNotEqualTo(0L);
  }

  @Test
  void getObjectSizeWithSpaceInKey() {
    String spacedKey = key2 + "/ /file";
    putObject(amazonS3, bucket, spacedKey, content);
    HeadObjectResponse result = s3Client.getObjectMetadata(bucket, spacedKey);
    assertThat(result.contentLength()).isNotEqualTo(0L);
  }

  @Test
  void getObjectSizeForEmptyFile() {
    putObject(amazonS3, bucket, key2, "");
    HeadObjectResponse result = s3Client.getObjectMetadata(bucket, key2);
    assertThat(result.contentLength()).isEqualTo(0L);
  }

  @Test
  void isEmptyForNonEmptyDirectory() {
    putObject(amazonS3, bucket, key1, content);
    boolean result = s3Client.isEmpty(bucket, keyRoot, null);
    assertThat(result).isFalse();
  }

  @Test
  void isEmptyForNonEmptyDirectoryWithSpacedKey() {
    String spacedKey = key1 + "/ /file";
    putObject(amazonS3, bucket, spacedKey, content);
    boolean result = s3Client.isEmpty(bucket, keyRoot, null);
    assertThat(result).isFalse();
  }

  @Test
  void isEmptyForEmptyDirectory() {
    boolean result = s3Client.isEmpty(bucket, keyRoot, null);
    assertThat(result).isTrue();
  }

  @Test
  void isEmptyDryRunForNonEmptyDirectoryAndCorrectLeafKey() {
    putObject(amazonS3, bucket, key1, content);
    boolean result = s3ClientDryRun.isEmpty(bucket, "table", keyRoot);
    assertThat(result).isTrue();
  }

  @Test
  void isEmptyDryRunForEmptyDirectory() {
    boolean result = s3ClientDryRun.isEmpty(bucket, "table", keyRoot);
    assertThat(result).isTrue();
  }

  @Test
  void isEmptyDryRunForOtherHighLevelDirectory() {
    putObject(amazonS3, bucket, "table/partition_2", content);
    boolean result = s3ClientDryRun.isEmpty(bucket, "table", keyRoot);
    assertThat(result).isFalse();
  }

  @Test
  void doesObjectExist() {
    putObject(amazonS3, bucket, key1, content);
    boolean result = s3Client.doesObjectExist(bucket, key1);
    assertThat(result).isTrue();
  }

  @Test
  void doesObjectExistWithSpacedKey() {
    String spacedKey = key1 + "/ /file";
    putObject(amazonS3, bucket, spacedKey, content);
    boolean result = s3Client.doesObjectExist(bucket, spacedKey);
    assertThat(result).isTrue();
  }

  @Test
  void isEmptyDryRunForCommonFolderName() {
    String otherPartition = "table/test/test/partition10";
    String folder1 = "table";
    String folder2 = "table/test";
    String folder3 = "table/test/test";
    String folder4 = "table/test/test/partition1";

    String filePath = "table/test/test/partition1/test";
    String sentinel1 = "table_$folder$";
    String sentinel2 = "table/test_$folder$";
    String sentinel3 = "table/test/test_$folder$";
    String sentinel4 = "table/test/test/partition1_$folder$";
    putObject(amazonS3, bucket, filePath, content);
    putObject(amazonS3, bucket, sentinel1, "");
    putObject(amazonS3, bucket, sentinel2, "");
    putObject(amazonS3, bucket, sentinel3, "");
    putObject(amazonS3, bucket, sentinel4, "");

    assertThat(s3ClientDryRun.isEmpty(bucket, folder3, folder4)).isTrue();
    assertThat(s3ClientDryRun.isEmpty(bucket, folder3, otherPartition)).isFalse();
    assertThat(s3ClientDryRun.isEmpty(bucket, folder2, folder3)).isTrue();
    assertThat(s3ClientDryRun.isEmpty(bucket, folder1, folder2)).isTrue();
  }
}
