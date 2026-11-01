package com.kamerzing.payment_intelligence.ai;

/** Safe bounded reason code only; never stores exception text or model reasoning. */
public final class AiFailure extends RuntimeException {
	public AiFailure(String code) { super(code); }
}
