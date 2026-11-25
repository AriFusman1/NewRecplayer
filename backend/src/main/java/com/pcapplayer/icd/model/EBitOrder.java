package com.pcapplayer.icd.model;

/**
 * How the bits of a bitfield are packed into their containing integer.
 * <ul>
 *   <li>{@code MSB_FIRST}: the first bit field takes the most significant bits of the container.</li>
 *   <li>{@code LSB_FIRST}: the first bit field takes the least significant bits.</li>
 * </ul>
 */
public enum EBitOrder {
    MSB_FIRST,
    LSB_FIRST
}
