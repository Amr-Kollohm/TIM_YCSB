package site.ycsb.db;

import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.DBException;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * YCSB binding for a custom Axum S3-like caching server.
 */
public class TimClient extends DB {

  private static HttpClient httpClient;
  private static String endpoint;
  private static String authToken;
  private static int connectionTimeoutMillis;
  private static int readTimeoutMillis;

  // To manage shared client instance across multiple YCSB threads
  private static final AtomicInteger INIT_COUNT = new AtomicInteger(0);

  @Override
  public void init() throws DBException {
    final int count = INIT_COUNT.incrementAndGet();
    if (count == 1) {
      synchronized (TimClient.class) {
        if (httpClient == null) {
          Properties props = getProperties();

          endpoint = props.getProperty("tim.endpoint", "http://localhost:3000");
          authToken = props.getProperty("tim.auth_token");
          connectionTimeoutMillis = Integer.parseInt(props.getProperty("tim.connectionTimeout", "5000"));
          readTimeoutMillis = Integer.parseInt(props.getProperty("tim.readTimeout", "10000"));

          HttpClient.Builder clientBuilder = HttpClient.newBuilder()
              .connectTimeout(Duration.ofMillis(connectionTimeoutMillis));

          httpClient = clientBuilder.build();
          System.out.println("TimClient initialized. Endpoint: " + endpoint);
        }
      }
    }
  }

  @Override
  public void cleanup() throws DBException {
    if (INIT_COUNT.decrementAndGet() == 0) {
      System.out.println("TimClient cleanup complete.");
    }
  }

  @Override
  public Status read(String bucket, String key, Set<String> fields, Map<String, ByteIterator> result) {
    try {
      String url = String.format("%s/%s/%s", endpoint, bucket, key);
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .GET()
          .timeout(Duration.ofMillis(readTimeoutMillis));

      if (authToken != null && !authToken.isEmpty()) {
        requestBuilder.header("Authorization", "Bearer " + authToken);
      }

      HttpRequest request = requestBuilder.build();
      HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      int statusCode = response.statusCode();

      if (statusCode == 200) {
        byte[] body = response.body();
        if (body != null && body.length > 0) {
          result.put("data", new StringByteIterator(new String(body)));
        }
        return Status.OK;
      } else if (statusCode == 404) {
        return Status.NOT_FOUND;
      } else {
        System.err.println("Read failed for key: " + key + ", Status: " + statusCode);
        return Status.ERROR;
      }
    } catch (IOException | InterruptedException e) {
      System.err.println("Error during read operation for key: " + key + ": " + e.getMessage());
      return Status.ERROR;
    }
  }

  @Override
  public Status insert(String bucket, String key, Map<String, ByteIterator> values) {
    return putObject(bucket, key, values);
  }

  @Override
  public Status update(String bucket, String key, Map<String, ByteIterator> values) {
    return putObject(bucket, key, values);
  }

  @Override
  public Status delete(String bucket, String key) {
    try {
      String url = String.format("%s/%s/%s", endpoint, bucket, key);
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .DELETE()
          .timeout(Duration.ofMillis(readTimeoutMillis));

      if (authToken != null && !authToken.isEmpty()) {
        requestBuilder.header("Authorization", "Bearer " + authToken);
      }

      HttpRequest request = requestBuilder.build();
      HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      int statusCode = response.statusCode();

      if (statusCode == 200 || statusCode == 204) {
        return Status.OK;
      } else if (statusCode == 404) {
        return Status.NOT_FOUND;
      } else {
        System.err.println("Delete failed for key: " + key + ", Status: " + statusCode);
        return Status.ERROR;
      }
    } catch (IOException | InterruptedException e) {
      System.err.println("Error during delete operation for key: " + key + ": " + e.getMessage());
      return Status.ERROR;
    }
  }

  private Status putObject(String bucket, String key, Map<String, ByteIterator> values) {
    try {
      // Extract first field value as data payload
      byte[] data = null;
      if (!values.isEmpty()) {
        ByteIterator it = values.values().iterator().next();
        data = it.toArray();
      }

      String url = String.format("%s/%s/%s", endpoint, bucket, key);
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .PUT(data != null ?
              HttpRequest.BodyPublishers.ofByteArray(data) :
              HttpRequest.BodyPublishers.noBody())
          .timeout(Duration.ofMillis(readTimeoutMillis));

      if (authToken != null && !authToken.isEmpty()) {
        requestBuilder.header("Authorization", "Bearer " + authToken);
      }

      HttpRequest request = requestBuilder.build();
      HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      int statusCode = response.statusCode();

      if (statusCode == 200 || statusCode == 204) {
        return Status.OK;
      } else {
        System.err.println("Put operation failed for key: " + key + ", Status: " + statusCode);
        return Status.ERROR;
      }
    } catch (IOException | InterruptedException e) {
      System.err.println("Error during put operation for key: " + key + ": " + e.getMessage());
      return Status.ERROR;
    }
  }

  @Override
  public Status scan(String bucket, String startkey, int recordcount, Set<String> fields,
                     java.util.Vector<HashMap<String, ByteIterator>> result) {
    String prefix = getProperties().getProperty("tim.keyprefix", "user");
    int keyLength = Integer.parseInt(getProperties().getProperty("tim.keylength", "8"));

    if (!startkey.startsWith(prefix)) {
      System.err.println("Scan error: startkey '" + startkey + "' doesn't match expected prefix '" + prefix + "'");
      return Status.ERROR;
    }

    // Extract numeric part from startkey
    String numericPart = startkey.substring(prefix.length());
    int startNum;
    try {
      startNum = Integer.parseInt(numericPart);
    } catch (NumberFormatException e) {
      System.err.println("Scan error: Couldn't parse numeric part of startkey '" + startkey + "'");
      return Status.ERROR;
    }

    int consecutiveMisses = 0;
    int keysRetrieved = 0;
    int currentNum = startNum;

    while (keysRetrieved < recordcount && consecutiveMisses < 100) {
      // Format key with zero-padding
      String currentKey = String.format("%s%0" + keyLength + "d", prefix, currentNum);
      currentNum++;

      Map<String, ByteIterator> keyResult = new HashMap<>();
      Status readStatus = read(bucket, currentKey, fields, keyResult);

      if (readStatus == Status.OK) {
        result.add(new HashMap<>(keyResult));
        keysRetrieved++;
        consecutiveMisses = 0;  // Reset consecutive misses counter
      } else if (readStatus == Status.NOT_FOUND) {
        consecutiveMisses++;
      } else {
        // Unexpected error
        System.err.println("Scan error during read of key '" + currentKey + "': " + readStatus.getName());
        return readStatus;
      }
    }

    if (keysRetrieved > 0) {
      return Status.OK;
    } else {
      System.err.println("Scan error: No keys found starting from '" + startkey + "'");
      return Status.ERROR;
    }
  }
}