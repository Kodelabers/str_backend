package com.str.backend.objection;

/**
 * Kartice iznad popisa očitovanja i prigovora: neriješeni podnesci u opsegu službenika — ukupno, zaprimljeni
 * danas (po hrvatskom vremenu) i stariji od pet dana — te ukupan broj riješenih (UAT 25.11.). Ne ovise o
 * filtrima popisa; {@code totalPending + resolved} = svi podnesci u opsegu.
 */
public record AdminObjectionStatsDto(long totalPending, long receivedToday, long olderThan5Days, long resolved) {
}
