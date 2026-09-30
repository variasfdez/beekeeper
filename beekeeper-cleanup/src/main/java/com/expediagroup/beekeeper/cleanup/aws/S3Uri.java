/**
 * Copyright (C) 2026 Expedia, Inc.
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Parses {@code s3://bucket/key} strings. Keys are returned exactly as they appear in the input:
 * percent sequences such as {@code %3A} (common in Hive partition directory names) are part of the
 * key and are NOT decoded.
 */
final class S3Uri {

  private final String bucket;
  private final String key;

  private S3Uri(String bucket, String key) {
    this.bucket = bucket;
    this.key = key;
  }

  static S3Uri parse(String str) {
    URI uri = URI.create(encode(str));
    if (!"s3".equalsIgnoreCase(uri.getScheme())) {
      if (uri.getHost() == null) {
        throw new IllegalArgumentException("Invalid S3 URI: no hostname: " + uri);
      }
      throw new IllegalArgumentException(
          "Invalid S3 URI: hostname does not appear to be a valid S3 endpoint: " + uri);
    }
    String bucket = uri.getAuthority();
    if (bucket == null) {
      throw new IllegalArgumentException("Invalid S3 URI: no bucket: " + uri);
    }
    String path = uri.getPath();
    String key = path.length() <= 1 ? null : path.substring(1);
    return new S3Uri(bucket, key);
  }

  /**
   * Percent-encodes everything except ':' and '/' so that {@link URI#getPath()} decodes back to the
   * literal input.
   */
  private static String encode(String str) {
    return URLEncoder.encode(str, StandardCharsets.UTF_8)
        .replace("%3A", ":")
        .replace("%2F", "/")
        .replace("+", "%20");
  }

  String getBucket() {
    return bucket;
  }

  String getKey() {
    return key;
  }
}
