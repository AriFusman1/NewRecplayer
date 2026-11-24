package com.pcapplayer.icd.tree;

import com.pcapplayer.icd.model.EFieldType;

import java.util.List;

/**
 * One field of a message, for display and for checking time-fix profiles.
 *
 * @param path        the field's path; array elements are written {@code [*]} ({@code targets[*].seenGps})
 * @param type        what to show as the type: "UINT32", "struct Target", "enum StatusCode (UINT8)", "3 bits"
 * @param storageType for numbers and enums: how the value is stored (a bit's container type); else null
 * @param bitWidth    for a bit: its width; else 0
 * @param size        bytes of one element (header and structs: their total), null when it depends on a count
 * @param array       null, or the array length: a number, or the field holding the count
 * @param numeric     true if the field can hold a time: a number (not BOOL, not an enum) or a bit
 * @param inArray     true if the field is an array element or inside one
 */
public record IcdTreeNode(String name, String path, ETreeNodeKind kind, String type, EFieldType storageType, int bitWidth,
                          Integer size, String array, boolean numeric, boolean inArray, List<IcdTreeNode> children) {
}
