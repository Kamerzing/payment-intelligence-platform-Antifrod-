package com.kamerzing.payment_intelligence.common;

import com.kamerzing.payment_intelligence.common.web.ApiException;
import org.springframework.http.HttpStatus;

public final class MinorUnits {
	private MinorUnits() { }
	public static long parse(String value, boolean positive) {
		if (value == null || value.length() > 19 || !value.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*")) throw invalid();
		try { return Long.parseLong(value); } catch (NumberFormatException exception) { throw invalid(); }
	}
	public static void total(long amount, long fee) {
		try { Math.addExact(amount, fee); } catch (ArithmeticException exception) { throw invalid(); }
	}
	public static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid command parameters."); }
}
