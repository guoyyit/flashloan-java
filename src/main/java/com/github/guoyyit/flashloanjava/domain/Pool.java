package com.github.guoyyit.flashloanjava.domain;

import java.time.Instant;

public record Pool(long id, String currency, long balance, long version, Instant createdAt) {
}
