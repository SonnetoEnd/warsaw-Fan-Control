/* SPDX-License-Identifier: GPL-2.0-only */
SECTIONS {
    .plt 0 : { BYTE(0) }
    .init.plt 0 : { BYTE(0) }
    .text.ftrace_trampoline 0 : { BYTE(0) }
    __kcfi_traps : { KEEP(*(.kcfi_traps)) }
    /DISCARD/ : { *(.debug_*) *(.eh_frame) *(.comment) *(.note.GNU-stack) }
}
