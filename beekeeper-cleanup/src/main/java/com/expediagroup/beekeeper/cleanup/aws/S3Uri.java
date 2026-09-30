/**
 * Copyright (C) 2019-2026 Expedia, Inc.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.expediagroup.beekeeper.cleanup.aws;

/**
 * Parses an "s3://bucket/key" URI into its bucket and key.
 *
 * <p>Replaces AmazonS3URI, which has no equivalent in AWS SDK for Java v2. Keys are taken verbatim,
 * without URL decoding, which is what AmazonS3URI did when constructed with urlEncode=true: keys
 * can therefore contain characters which are not valid in a java.net.URI, such as spaces, and
 * encoded characters are left encoded.
 */
class S3Uri {

  private static final String S3_SCHEME = "s3://";
  private static final String SCHEME_SEPARATOR = "://";

  private final String bucket;
  private final String key;

  S3Uri(String uri) {
    if (!uri.contains(SCHEME_SEPARATOR)) {
      throw new IllegalArgumentException("Invalid S3 URI: no hostname: " + uri);
    }
    if (!uri.startsWith(S3_SCHEME)) {
      throw new IllegalArgumentException(
          "Invalid S3 URI: hostname does not appear to be a valid S3 endpoint: " + uri);
    }
    String bucketAndKey = uri.substring(S3_SCHEME.length());
    int keySeparatorIndex = bucketAndKey.indexOf('/');
    this.bucket =
        keySeparatorIndex == -1 ? bucketAndKey : bucketAndKey.substring(0, keySeparatorIndex);
    if (bucket.isEmpty()) {
      throw new IllegalArgumentException("Invalid S3 URI: no bucket: " + uri);
    }
    String parsedKey =
        keySeparatorIndex == -1 ? null : bucketAndKey.substring(keySeparatorIndex + 1);
    this.key = parsedKey == null || parsedKey.isEmpty() ? null : parsedKey;
  }

  String getBucket() {
    return bucket;
  }

  String getKey() {
    return key;
  }
}
