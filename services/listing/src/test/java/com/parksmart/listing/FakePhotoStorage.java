package com.parksmart.listing;

import com.parksmart.listing.application.port.PhotoStorage;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory object storage for integration tests. Tests "upload" with {@link #put}. */
public class FakePhotoStorage implements PhotoStorage {

  public static final byte[] PNG =
      new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H'};

  private final Map<String, Stored> objects = new ConcurrentHashMap<>();
  private final List<String> deleted = new CopyOnWriteArrayList<>();

  record Stored(String contentType, byte[] bytes) {}

  public void put(String objectKey, String contentType, byte[] bytes) {
    objects.put(objectKey, new Stored(contentType, bytes));
  }

  /** A PNG-signed file padded to the given size. */
  public static byte[] png(int size) {
    return Arrays.copyOf(PNG, size);
  }

  public boolean exists(String objectKey) {
    return objects.containsKey(objectKey);
  }

  public List<String> deleted() {
    return deleted;
  }

  @Override
  public PresignedUpload presignPut(
      String objectKey, String contentType, long contentLength, Duration ttl) {
    return new PresignedUpload(
        "https://storage.test/" + objectKey,
        Map.of("Content-Type", contentType),
        Instant.now().plus(ttl));
  }

  @Override
  public Optional<StoredObject> stat(String objectKey) {
    return Optional.ofNullable(objects.get(objectKey))
        .map(s -> new StoredObject(s.contentType(), s.bytes().length));
  }

  @Override
  public byte[] readHead(String objectKey, int bytes) {
    byte[] all = objects.get(objectKey).bytes();
    return Arrays.copyOf(all, Math.min(bytes, all.length));
  }

  @Override
  public void copy(String fromKey, String toKey) {
    objects.put(toKey, objects.get(fromKey));
  }

  @Override
  public void delete(String objectKey) {
    objects.remove(objectKey);
    deleted.add(objectKey);
  }

  @Override
  public String publicUrl(String objectKey) {
    return "https://photos.parksmart.test/" + objectKey;
  }
}
