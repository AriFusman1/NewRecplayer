package com.pcapplayer.recordManagement.model;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;

/**
 * A record category; its connection mode is fixed at creation.
 *
 * @param icdId the ICD its records' messages follow, or null (records then play without decoding)
 */
public record Category(String id, String name, EConnectionMode mode, long createdAtMs, String icdId) {
}
