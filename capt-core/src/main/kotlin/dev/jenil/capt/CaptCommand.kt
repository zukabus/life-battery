package dev.jenil.capt

/**
 * CAPT command codes, from captdriver's `src/capt-command.h`.
 * (C) 2013 Alexey Galakhov, GPLv3.
 *
 * Every command travels in a 4-byte frame: command as little-endian u16,
 * then total frame length (payload + 4) as little-endian u16, then payload.
 */
object CaptCommand {
    const val NOP = 0xA0A0
    const val CHKJOBSTAT = 0xA0A1
    const val CHKXSTATUS = 0xA0A8

    const val IEEE_IDENT = 0xA1A0   // raw reply, not framed
    const val IDENT = 0xA1A1

    const val JOB_BEGIN = 0xA2A0

    const val START_0 = 0xA3A2

    const val PRINT_DATA = 0xC0A0
    const val PRINT_DATA_END = 0xC0A4

    const val SET_PARM_PAGE = 0xD0A0
    const val SET_PARM_1 = 0xD0A1
    const val SET_PARM_2 = 0xD0A2
    const val SET_PARM_HISCOA = 0xD0A4
    const val SET_PARMS = 0xD0A9

    const val CHKSTATUS = 0xE0A0
    const val START_2 = 0xE0A2
    const val START_1 = 0xE0A3
    const val START_3 = 0xE0A4
    const val UPLOAD_2 = 0xE0A5
    const val FIRE = 0xE0A7
    const val JOB_END = 0xE0A9

    const val JOB_SETUP = 0xE1A1
    const val GPIO = 0xE1A2

    fun name(cmd: Int): String = when (cmd) {
        NOP -> "NOP"
        CHKJOBSTAT -> "CHKJOBSTAT"
        CHKXSTATUS -> "CHKXSTATUS"
        IEEE_IDENT -> "IEEE_IDENT"
        IDENT -> "IDENT"
        JOB_BEGIN -> "JOB_BEGIN"
        START_0 -> "START_0"
        PRINT_DATA -> "PRINT_DATA"
        PRINT_DATA_END -> "PRINT_DATA_END"
        SET_PARM_PAGE -> "SET_PARM_PAGE"
        SET_PARM_1 -> "SET_PARM_1"
        SET_PARM_2 -> "SET_PARM_2"
        SET_PARM_HISCOA -> "SET_PARM_HISCOA"
        SET_PARMS -> "SET_PARMS"
        CHKSTATUS -> "CHKSTATUS"
        START_1 -> "START_1"
        START_2 -> "START_2"
        START_3 -> "START_3"
        UPLOAD_2 -> "UPLOAD_2"
        FIRE -> "FIRE"
        JOB_END -> "JOB_END"
        JOB_SETUP -> "JOB_SETUP"
        GPIO -> "GPIO"
        else -> "0x%04X".format(cmd)
    }
}
