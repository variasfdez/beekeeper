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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

import java.net.URI;
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
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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
    emptyBucket(bucket);
    assertThat(listObjects(bucket, null)).isEmpty();
    s3Client = new S3Client(amazonS3, false);
    s3ClientDryRun = new S3Client(amazonS3, true);
  }

  private void createBucket(String bucket) {
    if (amazonS3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) {
      amazonS3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    }
  }

  private void emptyBucket(String bucket) {
    ListObjectsV2Response listObjectsV2Response;
    String continuationToken = null;
    do {
      ListObjectsV2Request request =
          ListObjectsV2Request.builder()
              .bucket(bucket)
              .continuationToken(continuationToken)
              .build();
      listObjectsV2Response = amazonS3.listObjectsV2(request);
      List<ObjectIdentifier> keys =
          listObjectsV2Response.contents().stream()
              .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build())
              .collect(Collectors.toList());
      if (keys.size() > 0) {
        amazonS3.deleteObjects(
            DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(keys).build())
                .build());
      }
      continuationToken = listObjectsV2Response.nextContinuationToken();
    } while (Boolean.TRUE.equals(listObjectsV2Response.isTruncated()));
  }

  private void putObject(String bucket, String key, String content) {
    amazonS3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).build(),
        RequestBody.fromString(content));
  }

  private List<S3Object> listObjects(String bucket, String prefix) {
    return amazonS3
        .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
        .contents();
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
  void deleteObject() {
    putObject(bucket, key1, content);
    s3Client.deleteObject(bucket, key1);
    assertThat(objectExists(bucket, key1)).isFalse();
  }

  @Test
  void deleteObjectWithSpace() {
    String spacedKey = keyRoot + "/ /file";
    putObject(bucket, spacedKey, content);
    s3Client.deleteObject(bucket, spacedKey);
    assertThat(objectExists(bucket, spacedKey)).isFalse();
  }

  @Test
  void deleteObjectsWithSpace() {
    String spacedKey1 = keyRoot + "/ /file1";
    String spacedKey2 = keyRoot + "/ /file2";
    putObject(bucket, spacedKey1, content);
    putObject(bucket, spacedKey2, content);
    s3Client.deleteObjects(bucket, List.of(spacedKey1, spacedKey2));
    assertThat(objectExists(bucket, spacedKey1)).isFalse();
    assertThat(objectExists(bucket, spacedKey2)).isFalse();
  }

  @Test
  void deleteObjectDryRun() {
    putObject(bucket, key1, content);
    s3ClientDryRun.deleteObject(bucket, key1);
    assertThat(objectExists(bucket, key1)).isTrue();
  }

  @Test
  void listObjects() {
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    List<S3Object> result = s3Client.listObjects(bucket, keyRoot);

    assertThat(result.size()).isEqualTo(2);
    assertThat(result.get(0).key()).isEqualTo(key1);
    assertThat(objectExists(bucket, result.get(0).key())).isTrue();
    assertThat(result.get(1).key()).isEqualTo(key2);
    assertThat(objectExists(bucket, result.get(1).key())).isTrue();
  }

  @Test
  void listObjectsWithSpace() {
    String spacedKey1 = keyRoot + "/ /file1";
    String spacedKey2 = keyRoot + "/ /file2";
    putObject(bucket, spacedKey1, content);
    putObject(bucket, spacedKey2, content);

    List<S3Object> result = s3Client.listObjects(bucket, keyRoot);

    assertThat(result.size()).isEqualTo(2);
    assertThat(result.get(0).key()).isEqualTo(spacedKey1);
    assertThat(objectExists(bucket, result.get(0).key())).isTrue();
    assertThat(result.get(1).key()).isEqualTo(spacedKey2);
    assertThat(objectExists(bucket, result.get(1).key())).isTrue();
  }

  @Test
  void listObjectsWithSpaceInSearch() {
    String spacedKeyRoot = keyRoot + "/ /";
    String spacedKey1 = spacedKeyRoot + "file1";
    String spacedKey2 = spacedKeyRoot + "file2";
    putObject(bucket, spacedKey1, content);
    putObject(bucket, spacedKey2, content);

    List<S3Object> result = s3Client.listObjects(bucket, spacedKeyRoot);

    assertThat(result.size()).isEqualTo(2);
    assertThat(result.get(0).key()).isEqualTo(spacedKey1);
    assertThat(objectExists(bucket, result.get(0).key())).isTrue();
    assertThat(result.get(1).key()).isEqualTo(spacedKey2);
    assertThat(objectExists(bucket, result.get(1).key())).isTrue();
  }

  @Test
  void listBatchObjects() {
    int s3BatchSize = 1000;
    int extraKeys = 100;
    List<String> keys = new ArrayList<>();
    for (int i = 1; i <= s3BatchSize + extraKeys; i++) {
      keys.add(keyRoot + "/file" + i);
    }
    keys.parallelStream().forEach(key -> putObject(bucket, key, content));

    List<S3Object> result = s3Client.listObjects(bucket, keyRoot);

    assertThat(result.size()).isEqualTo(s3BatchSize + extraKeys);
  }

  @Test
  void deleteObjectsStopsAtFirstBatchWithErrors() {
    software.amazon.awssdk.services.s3.S3Client mockAmazonS3 =
        Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class);
    int s3BatchSize = 1000;
    List<String> keys = new ArrayList<>();
    for (int i = 1; i <= s3BatchSize + 1; i++) {
      keys.add(keyRoot + "/file" + i);
    }
    S3Error error =
        S3Error.builder().key(keys.get(0)).code("AccessDenied").message("Access Denied").build();
    Mockito.when(mockAmazonS3.deleteObjects(Mockito.any(DeleteObjectsRequest.class)))
        .thenReturn(DeleteObjectsResponse.builder().errors(error).build());
    S3Client s3ClientWithMock = new S3Client(mockAmazonS3, false);

    assertThatExceptionOfType(S3Exception.class)
        .isThrownBy(() -> s3ClientWithMock.deleteObjects(bucket, keys))
        .withMessage(
            "Failed to delete objects from bucket \"bucket\": "
                + "'table/partition_1/file1' (AccessDenied: Access Denied)");
    Mockito.verify(mockAmazonS3, Mockito.times(1))
        .deleteObjects(Mockito.any(DeleteObjectsRequest.class));
    verifyNoMoreInteractions(mockAmazonS3);
  }

  @Test
  void deleteObjectsInDirectory() {
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    List<String> result = s3Client.deleteObjects(bucket, List.of(key1, key2));

    assertThat(result.size()).isEqualTo(2);
    assertThat(result).contains(key1);
    assertThat(result).contains(key2);
    assertThat(objectExists(bucket, key1)).isFalse();
    assertThat(objectExists(bucket, key2)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = { 500, 1000, 1500 })
  void splitDeleteObjectsInDirectory(final int totalObjects) {
    ArrayList<String> keys = new ArrayList<>();
    for (int i = 1; i <= totalObjects; i++) {
      var key = keyRoot + "/file" + i;
      keys.add(key);
    }
    keys.parallelStream().forEach(key -> putObject(bucket, key, content));

    List<String> result = s3Client.deleteObjects(bucket, keys);
    assertThat(result.size()).isEqualTo(totalObjects);

    int numberOfObjectsLeft = listObjects(bucket, keyRoot).size();
    assertThat(numberOfObjectsLeft).isEqualTo(0);

    assertThat(keys).isEqualTo(result);
  }

  @Test
  void deleteObjectsInDirectoryDryRun() {
    putObject(bucket, key1, content);
    putObject(bucket, key2, content);

    List<String> result = s3ClientDryRun.deleteObjects(bucket, List.of(key1, key2));

    assertThat(result.size()).isEqualTo(2);
    assertThat(result).contains(key1);
    assertThat(result).contains(key2);
    assertThat(objectExists(bucket, key1)).isTrue();
    assertThat(objectExists(bucket, key2)).isTrue();
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
  void doesObjectExistForFile() {
    putObject(bucket, key2, content);
    boolean result = s3Client.doesObjectExist(bucket, key2);
    assertThat(result).isTrue();
  }

  @Test
  void doesObjectExistForFileWithSpaceInKey() {
    String spacedKey = key2 + "/ /file";
    putObject(bucket, spacedKey, content);
    boolean result = s3Client.doesObjectExist(bucket, spacedKey);
    assertThat(result).isTrue();
  }

  @Test
  void doesObjectExistForDirectory() {
    putObject(bucket, key2, content);
    boolean result = s3Client.doesObjectExist(bucket, keyRoot);
    assertThat(result).isFalse();
  }

  @Test
  void doesObjectExistForDirectoryWithTrailingSlash() {
    putObject(bucket, key2, content);
    boolean result = s3Client.doesObjectExist(bucket, keyRoot + "/");
    assertThat(result).isFalse();
  }

  @Test
  void getObjectSize() {
    putObject(bucket, key2, content);
    HeadObjectResponse result = s3Client.getObjectMetadata(bucket, key2);
    assertThat(result.contentLength()).isNotEqualTo(0L);
  }

  @Test
  void getObjectSizeWithSpaceInKey() {
    String spacedKey = key2 + "/ /file";
    putObject(bucket, spacedKey, content);
    HeadObjectResponse result = s3Client.getObjectMetadata(bucket, spacedKey);
    assertThat(result.contentLength()).isNotEqualTo(0L);
  }

  @Test
  void getObjectSizeForEmptyFile() {
    putObject(bucket, key2, "");
    HeadObjectResponse result = s3Client.getObjectMetadata(bucket, key2);
    assertThat(result.contentLength()).isEqualTo(0L);
  }

  @Test
  void isEmptyForNonEmptyDirectory() {
    putObject(bucket, key1, content);
    boolean result = s3Client.isEmpty(bucket, keyRoot, null);
    assertThat(result).isFalse();
  }

  @Test
  void isEmptyForNonEmptyDirectoryWithSpacedKey() {
    String spacedKey = key1 + "/ /file";
    putObject(bucket, spacedKey, content);
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
    putObject(bucket, key1, content);
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
    putObject(bucket, "table/partition_2", content);
    boolean result = s3ClientDryRun.isEmpty(bucket, "table", keyRoot);
    assertThat(result).isFalse();
  }

  @Test
  void doesObjectExist() {
    putObject(bucket, key1, content);
    boolean result = s3Client.doesObjectExist(bucket, key1);
    assertThat(result).isTrue();
  }

  @Test
  void doesObjectExistWithSpacedKey() {
    String spacedKey = key1 + "/ /file";
    putObject(bucket, spacedKey, content);
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
    putObject(bucket, filePath, content);
    putObject(bucket, sentinel1, "");
    putObject(bucket, sentinel2, "");
    putObject(bucket, sentinel3, "");
    putObject(bucket, sentinel4, "");

    assertThat(s3ClientDryRun.isEmpty(bucket, folder3, folder4)).isTrue();
    assertThat(s3ClientDryRun.isEmpty(bucket, folder3, otherPartition)).isFalse();
    assertThat(s3ClientDryRun.isEmpty(bucket, folder2, folder3)).isTrue();
    assertThat(s3ClientDryRun.isEmpty(bucket, folder1, folder2)).isTrue();
  }
}
