/*
 * KOTLIN DIAGNOSTICS SPEC TEST (POSITIVE)
 *
 * SPEC VERSION: 0.1-100
 * PLACE: expressions, constant-literals, real-literals -> paragraph 3 -> sentence 2
 * NUMBER: 3
 * DESCRIPTION: Real literals with omitted a fraction part and an exponent mark, suffixed by f/F (float suffix).
 */

// TESTCASE NUMBER: 1
val value_1 = 0e0f

// TESTCASE NUMBER: 2
val value_2 = 00e00F

// TESTCASE NUMBER: 3
val value_3 = 000E-10f

// TESTCASE NUMBER: 4
val value_4 = 0000e+00000000000f

// TESTCASE NUMBER: 5
val value_5 = 00000000000000000000000000000000000000E1F

// TESTCASE NUMBER: 6
val value_6 = 1e1F

// TESTCASE NUMBER: 7
val value_7 = 22E-1f

// TESTCASE NUMBER: 8
val value_8 = 333e-00000000000F

// TESTCASE NUMBER: 9
val value_9 = 4444E-99999999999999999f

// TESTCASE NUMBER: 10
val value_10 = 55555e10f

// TESTCASE NUMBER: 11
val value_11 = 666666E00010F

// TESTCASE NUMBER: 12
val value_12 = 7777777e09090909090F

// TESTCASE NUMBER: 13
val value_13 = 88888888e1234567890F

// TESTCASE NUMBER: 14
val value_14 = 999999999E1234567890f

// TESTCASE NUMBER: 15
val value_15 = 123456789e987654321F

// TESTCASE NUMBER: 16
val value_16 = 2345678E0f

// TESTCASE NUMBER: 17
val value_17 = 34567E+010f

// TESTCASE NUMBER: 18
val value_18 = 456e-09876543210F

// TESTCASE NUMBER: 19
val value_19 = 5e505f

// TESTCASE NUMBER: 20
val value_20 = 654e5F

// TESTCASE NUMBER: 21
val value_21 = 76543E-91823f

// TESTCASE NUMBER: 22
val value_22 = 8765432e+90F

// TESTCASE NUMBER: 23
val value_23 = 987654321e-1f
