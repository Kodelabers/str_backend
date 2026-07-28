package com.str.backend.lookup;

/**
 * @param code stabilna šifra vrste (FS_SOBA, FS_APARTMAN, ...). Fronta se veže na nju,
 *             a ne na {@code id} ili {@code name} — oboje se razlikuje među okolinama.
 */
public record AccommodationTypeResponse(String id, String name, String group, String code) {}
