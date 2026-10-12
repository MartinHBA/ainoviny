package sk.verejnydohlad;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Full-body subscriber: HTTP request timeout applies through body completion, with a memory ceiling. */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
    private final int max; private int received; private Flow.Subscription upstream;
    BoundedBody(int max) { this.max=max; }
    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    public void onSubscribe(Flow.Subscription subscription) { upstream=subscription; delegate.onSubscribe(subscription); }
    public void onNext(List<ByteBuffer> data) {
        for(ByteBuffer bytes:data) received+=bytes.remaining();
        if(received>max) { upstream.cancel(); delegate.onError(new IllegalArgumentException("HTTP response exceeds configured size limit")); }
        else delegate.onNext(data);
    }
    public void onError(Throwable error) { delegate.onError(error); }
    public void onComplete() { delegate.onComplete(); }
}
