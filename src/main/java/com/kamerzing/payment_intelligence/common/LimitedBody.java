package com.kamerzing.payment_intelligence.common;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels a response before it can exceed the in-memory budget. */
public final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
	private final int limit;
	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
	private final CompletableFuture<byte[]> result = new CompletableFuture<>();
	private Flow.Subscription subscription;
	public LimitedBody(int limit) { this.limit = limit; }
	@Override public CompletionStage<byte[]> getBody() { return result; }
	@Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
	@Override public void onNext(List<ByteBuffer> buffers) {
		for (var buffer : buffers) {
			if (buffer.remaining() > limit - bytes.size()) {
				subscription.cancel(); result.completeExceptionally(new IllegalStateException("Response exceeds its limit.")); return;
			}
			byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
		}
		subscription.request(1);
	}
	@Override public void onError(Throwable error) { result.completeExceptionally(error); }
	@Override public void onComplete() { result.complete(bytes.toByteArray()); }
}
