package com.pcapplayer.icd.model;

import java.util.List;

/** A named group of fields, sorted by their {@code order}. Used for nested structs. */
public record StructDef(String name, List<FieldDef> fields) {
}
