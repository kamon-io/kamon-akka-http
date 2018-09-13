package kamon.akka.http.javadsl;

import java.util.function.Function;
import java.util.function.Supplier;

import akka.http.javadsl.server.PathMatcher1;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.unmarshalling.Unmarshaller;

public class Directives {
    /** Same as akka's pathPrefix directive, but adds the path to the current Kamon trace operation name. */
    public static Route pathPrefix(String prefix, Supplier<Route> inner) {
        return akka.http.javadsl.server.Directives.pathPrefix(prefix, () ->
            appendPathToOperationName(prefix, inner)
        );
    }

    /** Same as akka's pathPrefix directive, but adds the path to the current Kamon trace operation name. */
    public static <T> Route pathPrefix(Unmarshaller<String,T> un, Function<T,Route> inner) {
        return akka.http.javadsl.server.Directives.pathPrefix(un, t ->
            appendPathToOperationName("{}", () -> inner.apply(t))
        );
    }

    /** Same as akka's pathPrefix directive, but adds the path to the current Kamon trace operation name. */
    public static Route pathPrefix(Function<String,Route> inner) {
        return akka.http.javadsl.server.Directives.pathPrefix(segment ->
            appendPathToOperationName("{}", () -> inner.apply(segment))
        );
    }

    /** Same as akka's path directive, but adds the path to the current Kamon trace operation name. */
    public static Route path(String prefix, Supplier<Route> inner) {
        return akka.http.javadsl.server.Directives.path(prefix, () ->
            appendPathToOperationName(prefix, inner)
        );
    }

    /** Same as akka's path directive, but adds the path to the current Kamon trace operation name. */
    public static <T> Route path(PathMatcher1<T> m, Function<T,Route> inner) {
        return akka.http.javadsl.server.Directives.path(m, t ->
            appendPathToOperationName("{}", () -> inner.apply(t))
        );
    }

    /** Same as akka's path directive, but adds the path to the current Kamon trace operation name. */
    public static Route path(Function<String,Route> inner) {
        return akka.http.javadsl.server.Directives.path(segment ->
            appendPathToOperationName("{}", () -> inner.apply(segment))
        );
    }

    private static Route appendPathToOperationName(String postfix, Supplier<Route> inner) {
        return akka.http.javadsl.server.Directives.mapRequest(req -> {
            CustomOperationNameGenerator.append("/", postfix);
            return req;
        }, inner);
    }
}
