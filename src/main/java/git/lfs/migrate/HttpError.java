package git.lfs.migrate;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.URIException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * HTTP exception with header information.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class HttpError extends IOException {
  @NotNull
  private static final Logger log = LoggerFactory.getLogger(HttpError.class);

  public HttpError(@NotNull HttpMethodBase http, @NotNull String message) {
    super("HTTP request error: " + message);
    log.error("HTTP request error:" + message + "\n" + dump(http));
  }

  @NotNull
  public static String dump(@NotNull HttpMethodBase http) {
    final StringBuilder sb = new StringBuilder();
    sb.append("Request:\n");
    sb.append("  ").append(http.getName()).append(" ").append(getUrl(http)).append("\n");
    for (Header header : http.getRequestHeaders()) {
      sb.append("  ").append(header.getName()).append(": ");
      if (!header.getName().equals("Authorization")) {
        sb.append(header.getValue());
      } else {
        int space = header.getValue().indexOf(' ');
        if (space > 0) {
          sb.append(header.getValue().substring(0, space + 1));
        }
        sb.append("*****");
      }
      sb.append("\n");
    }

    sb.append("Response: ").append(http.getStatusCode()).append(" ").append(http.getStatusText()).append("\n");
    for (Header header : http.getResponseHeaders()) {
      sb.append("  ").append(header.getName()).append(": ");
      if (!header.getName().equals("Authorization")) {
        sb.append(header.getValue());
      } else {
        int space = header.getValue().indexOf(' ');
        if (space > 0) {
          sb.append(header.getValue().substring(0, space + 1));
        }
        sb.append("*****");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

  private static String getUrl(HttpMethodBase http) {
    try {
      return http.getURI().toString();
    } catch (URIException e) {
      return "<unknown url>";
    }
  }
}
