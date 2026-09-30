/**
 * Copyright (C) 2019-2022 Expedia, Inc.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

public class S3Client {

  private static final int REQUEST_CHUNK_SIZE = 1000;
  private static final Logger log = LoggerFactory.getLogger(S3Client.class);
  private final software.amazon.awssdk.services.s3.S3Client s3;
  private final boolean dryRunEnabled;

  public S3Client(software.amazon.awssdk.services.s3.S3Client s3, boolean dryRunEnabled) {
    this.s3 = s3;
    this.dryRunEnabled = dryRunEnabled;
  }

  void deleteObject(String bucket, String key) {
    if (dryRunEnabled) {
      log.info("Dry run - deleting: \"{}/{}\"", bucket, key);
    } else {
      log.info("Deleting \"{}/{}\"", bucket, key);
      s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
  }

  List<S3Object> listObjects(String bucket, String key) {
    List<S3Object> objects = new ArrayList<>();
    ListObjectsV2Response response;
    String continuationToken = null;
    do {
      ListObjectsV2Request request = ListObjectsV2Request.builder()
          .bucket(bucket)
          .prefix(key)
          .continuationToken(continuationToken)
          .build();
      response = s3.listObjectsV2(request);
      objects.addAll(response.contents());
      continuationToken = response.nextContinuationToken();
    } while (Boolean.TRUE.equals(response.isTruncated()));
    return objects;
  }

  /**
   * Returns the keys S3 reports as deleted. If S3 reports per-key errors for a chunk, the errors are logged and no
   * further chunks are sent, so the returned list is shorter than {@code keys}; callers must treat that as a failure.
   */
  List<String> deleteObjects(String bucket, List<String> keys) {
    if (keys.isEmpty()) {
      return Collections.emptyList();
    }
    if (!dryRunEnabled) {
      log.info("Attempting to delete a total of {} objects, from [{}] to [{}]", keys.size(), keys.get(0),
          keys.get(keys.size() - 1));
      List<String> deletedKeys = new ArrayList<>();
      int totalKeys = keys.size();
      int indexStart;
      int indexEnd = 0;
      while (indexEnd < totalKeys) {
        indexStart = indexEnd;
        indexEnd = nextIndexEnd(indexStart, REQUEST_CHUNK_SIZE, totalKeys);
        List<ObjectIdentifier> objectIdentifiers = keys.subList(indexStart, indexEnd)
            .stream()
            .map(key -> ObjectIdentifier.builder().key(key).build())
            .collect(Collectors.toList());
        DeleteObjectsRequest deleteObjectsRequest = DeleteObjectsRequest.builder()
            .bucket(bucket)
            .delete(Delete.builder().objects(objectIdentifiers).build())
            .build();
        DeleteObjectsResponse response = s3.deleteObjects(deleteObjectsRequest);
        response.deleted().stream().map(DeletedObject::key).forEach(deletedKeys::add);
        if (response.hasErrors() && !response.errors().isEmpty()) {
          response.errors()
              .forEach(error -> log.error("Failed to delete \"{}/{}\": {} - {}", bucket, error.key(), error.code(),
                  error.message()));
          return deletedKeys;
        }
      }
      log.info("Successfully deleted {} objects", keys.size());
      return deletedKeys;
    } else {
      return keys.stream()
          .peek(key -> log.info("Dry run - deleting: \"{}/{}\"", bucket, key))
          .collect(Collectors.toList());
    }
  }

  boolean doesObjectExist(String bucket, String key) {
    requireNonEmpty(bucket, "bucketName");
    requireNonEmpty(key, "objectName");
    try {
      getObjectMetadata(bucket, key);
      return true;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  HeadObjectResponse getObjectMetadata(String bucket, String key) {
    return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
  }

  boolean isEmpty(String bucket, String key, String leafKey) {
    ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).prefix(key + "/").build();
    List<S3Object> objectsLeftAtPath = s3.listObjectsV2(request).contents();
    if (!dryRunEnabled) {
      return objectsLeftAtPath.size() == 0;
    } else {
      String leafKeySentinel = leafKey + S3SentinelFilesCleaner.SENTINEL_SUFFIX;

      for (S3Object s3Object : objectsLeftAtPath) {
        String currentKey = s3Object.key();
        if (!currentKey.startsWith(leafKey + "/") && !currentKey.equals(leafKeySentinel)) {
          return false;
        }
      }
      return true;
    }
  }

  private void requireNonEmpty(String value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(format("%s cannot be null", fieldName));
    }
    if (value.isEmpty()) {
      throw new IllegalArgumentException(format("%s cannot be empty", fieldName));
    }
  }

  private int nextIndexEnd(final int indexStart, final int chunkSize, final int totalKeys) {
    int calculatedNextIndexEnd = indexStart + chunkSize;
    return Math.min(calculatedNextIndexEnd, totalKeys);
  }
}
