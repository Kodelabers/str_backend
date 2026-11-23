package com.str.backend.objection;

/**
 * Kartice iznad popisa očitovanja i prigovora: neriješeni podnesci u opsegu službenika — ukupno, zaprimljeni
 * danas (po hrvatskom vremenu) i stariji od pet dana. Ne ovise o filtrima popisa.
 */
public record AdminObjectionStatsDto(long totalPending, long receivedToday, long olderThan5Days) {
}
