package com.str.backend.objection;

/**
 * Životni ciklus prigovora na prijedlog suspenzije / suspenziju RB-a.
 *
 * <p>{@code SUBMITTED} čeka službenika — po RB-u najviše jedan (changeset 145). Službenik ga
 * {@code ACCEPTED} uvažava (RB se vraća u {@code ACTIVE}) ili {@code REJECTED} ne uvažava.
 * {@code CLOSED} je zatvoren bez odluke jer je RB prigovor „pretekao": napustio je prijedlog
 * suspenzije i suspenziju drugim putem (povlačenje prijedloga, reaktivacija, opoziv, povlačenje).
 */
public enum SuspensionObjectionStatus {
    SUBMITTED,
    ACCEPTED,
    REJECTED,
    CLOSED
}
