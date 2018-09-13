package kamon.akka.http;

import akka.http.javadsl.model.headers.Host;
import akka.http.scaladsl.model.HttpRequest;
import kamon.Kamon;
import kamon.akka.http.AkkaHttp.OperationNameGenerator;
import kamon.trace.Span;

/**
 * Generates a fixed operation name for server traces, but allows that name to be replaced with
 * prefixable/postfixable names instead. This goes togeter with the path*() methods in {@link Directives}.
 */
public class CustomOperationNameGenerator implements OperationNameGenerator {
    private static final String DEFAULT = "unknown";

    @Override
    public String clientOperationName(HttpRequest request) {
        String host = request.uri().authority().host().address();
        if (host.isEmpty()) {
            host = request.getHeader(Host.class).map(h -> h.host().toString()).orElse("unknown-host");
        }
        int port = request.uri().authority().port();
        if (port == 0) {
            port = request.getHeader(Host.class).map(h -> h.port()).orElse(0);
        }
        if (port == 0) {
            if (request.uri().scheme().equals("http")) {
                port = 80;
            } else if (request.uri().scheme().equals("https")) {
                port = 443;
            }
        }
        return host + ":" + port;
    }

    @Override
    public String serverOperationName(HttpRequest request) {
        // We start out with "unknown", replacing this whenever append() is called.
        return DEFAULT;
    }

    /**
     * Appends the given string to the current operation name.
     */
    public static void append(String separator, String postfix) {
        Span span = Kamon.currentSpan();                                             // NOTEST (we don't want kamon active in unit tests)
        String n = span.operationName();                                             // NOTEST
        span.setOperationName(n.equals(DEFAULT) ? postfix : n + separator + postfix);// NOTEST
    }

    /**
     * Prepends the given string to the current operation name.
     */
    public static void prepend(String prefix, String separator) {
        Span span = Kamon.currentSpan();                                              // NOTEST
        String n = span.operationName();                                              // NOTEST
        span.setOperationName(n.equals(DEFAULT) ? prefix : prefix + separator + n);   // NOTEST
    }
}
