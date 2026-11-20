	.text
	.hidden	cadence_try_call                // -- Begin function cadence_try_call
	.globl	cadence_try_call
	.p2align	2
	.type	cadence_try_call,@function
cadence_try_call:                       // @cadence_try_call
.Lcxxg_func_begin0:
	.cfi_startproc
	.cfi_personality 156, cxxg_ref_gxx_personality
	.cfi_lsda 28, .Lcxxg_exception0
// %bb.0:
	stp	x29, x30, [sp, #-32]!           // 16-byte Folded Spill
	.cfi_def_cfa_offset 32
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	mov	x29, sp
	.cfi_def_cfa w29, 32
	.cfi_offset w19, -8
	.cfi_offset w20, -16
	.cfi_offset w30, -24
	.cfi_offset w29, -32
	.cfi_remember_state
	mov	x19, x2
	mov	x8, x0
.Lcxxg_tmp0:
	mov	x0, x1
	blr	x8
.Lcxxg_tmp1:
// %bb.1:
	mov	w0, wzr
.Lcxxg_BB0_2:
	.cfi_def_cfa wsp, 32
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldp	x29, x30, [sp], #32             // 16-byte Folded Reload
	.cfi_def_cfa_offset 0
	.cfi_restore w19
	.cfi_restore w20
	.cfi_restore w30
	.cfi_restore w29
	ret
.Lcxxg_BB0_3:
	.cfi_restore_state
.Lcxxg_tmp2:
	mov	x20, x1
	bl	__cxa_begin_catch
	cmp	w20, #2
	b.ne	.Lcxxg_BB0_8
// %bb.4:
	ldr	x8, [x0]
	ldur	x9, [x8, #-8]
	ldr	x10, [x9, #8]
	cbz	x10, .Lcxxg_BB0_16
// %bb.5:
	mov	x9, xzr
.Lcxxg_BB0_6:                                // =>This Inner Loop Header: Depth=1
	ldrb	w11, [x10, x9]
	cbz	w11, .Lcxxg_BB0_17
// %bb.7:                               //   in Loop: Header=BB0_6 Depth=1
	strb	w11, [x19, x9]
	add	x9, x9, #1
	cmp	x9, #95
	b.ne	.Lcxxg_BB0_6
	b	.Lcxxg_BB0_17
.Lcxxg_BB0_8:
.Lcxxg_tmp3:
	bl	__cxa_current_exception_type
.Lcxxg_tmp4:
// %bb.9:
	cbz	x0, .Lcxxg_BB0_14
// %bb.10:
	ldr	x9, [x0, #8]
	cbz	x9, .Lcxxg_BB0_14
// %bb.11:
	mov	x8, xzr
.Lcxxg_BB0_12:                               // =>This Inner Loop Header: Depth=1
	ldrb	w10, [x9, x8]
	cbz	w10, .Lcxxg_BB0_15
// %bb.13:                              //   in Loop: Header=BB0_12 Depth=1
	strb	w10, [x19, x8]
	add	x8, x8, #1
	cmp	x8, #95
	b.ne	.Lcxxg_BB0_12
	b	.Lcxxg_BB0_15
.Lcxxg_BB0_14:
	mov	x8, xzr
.Lcxxg_BB0_15:
	strb	wzr, [x19, x8]
	add	x8, x19, #96
	mov	w19, #-2                        // =0xfffffffe
	b	.Lcxxg_BB0_21
.Lcxxg_BB0_16:
	mov	x9, xzr
.Lcxxg_BB0_17:
	strb	wzr, [x19, x9]
	add	x19, x19, #96
	ldr	x8, [x8, #16]
	blr	x8
	mov	x8, xzr
	cbz	x0, .Lcxxg_BB0_20
.Lcxxg_BB0_18:                               // =>This Inner Loop Header: Depth=1
	ldrb	w9, [x0, x8]
	cbz	w9, .Lcxxg_BB0_20
// %bb.19:                              //   in Loop: Header=BB0_18 Depth=1
	strb	w9, [x19, x8]
	add	x8, x8, #1
	cmp	x8, #223
	b.ne	.Lcxxg_BB0_18
.Lcxxg_BB0_20:
	add	x8, x19, x8
	mov	w19, #-1                        // =0xffffffff
.Lcxxg_BB0_21:
	strb	wzr, [x8]
	bl	__cxa_end_catch
	mov	w0, w19
	b	.Lcxxg_BB0_2
.Lcxxg_BB0_22:
.Lcxxg_tmp5:
	mov	x19, x0
.Lcxxg_tmp6:
	bl	__cxa_end_catch
.Lcxxg_tmp7:
// %bb.23:
	mov	x0, x19
	bl	_Unwind_Resume
.Lcxxg_BB0_24:
.Lcxxg_tmp8:
	bl	cxxg_call_terminate
.Lcxxg_func_end0:
	.size	cadence_try_call, .Lcxxg_func_end0-cadence_try_call
	.cfi_endproc
	.section	.gcc_except_table,"a",@progbits
	.p2align	2, 0x0
cxxg_lsda0:
.Lcxxg_exception0:
	.byte	255                             // @LPStart Encoding = omit
	.byte	156                             // @TType Encoding = indirect pcrel sdata8
	.uleb128 .Lcxxg_ttbase0-.Lcxxg_ttbaseref0
.Lcxxg_ttbaseref0:
	.byte	1                               // Call site Encoding = uleb128
	.uleb128 .Lcxxg_cst_end0-.Lcxxg_cst_begin0
.Lcxxg_cst_begin0:
	.uleb128 .Lcxxg_tmp0-.Lcxxg_func_begin0           // >> Call Site 1 <<
	.uleb128 .Lcxxg_tmp1-.Lcxxg_tmp0                  //   Call between .Lcxxg_tmp0 and .Lcxxg_tmp1
	.uleb128 .Lcxxg_tmp2-.Lcxxg_func_begin0           //     jumps to .Lcxxg_tmp2
	.byte	3                               //   On action: 2
	.uleb128 .Lcxxg_tmp1-.Lcxxg_func_begin0           // >> Call Site 2 <<
	.uleb128 .Lcxxg_tmp3-.Lcxxg_tmp1                  //   Call between .Lcxxg_tmp1 and .Lcxxg_tmp3
	.byte	0                               //     has no landing pad
	.byte	0                               //   On action: cleanup
	.uleb128 .Lcxxg_tmp3-.Lcxxg_func_begin0           // >> Call Site 3 <<
	.uleb128 .Lcxxg_tmp4-.Lcxxg_tmp3                  //   Call between .Lcxxg_tmp3 and .Lcxxg_tmp4
	.uleb128 .Lcxxg_tmp5-.Lcxxg_func_begin0           //     jumps to .Lcxxg_tmp5
	.byte	0                               //   On action: cleanup
	.uleb128 .Lcxxg_tmp4-.Lcxxg_func_begin0           // >> Call Site 4 <<
	.uleb128 .Lcxxg_tmp6-.Lcxxg_tmp4                  //   Call between .Lcxxg_tmp4 and .Lcxxg_tmp6
	.byte	0                               //     has no landing pad
	.byte	0                               //   On action: cleanup
	.uleb128 .Lcxxg_tmp6-.Lcxxg_func_begin0           // >> Call Site 5 <<
	.uleb128 .Lcxxg_tmp7-.Lcxxg_tmp6                  //   Call between .Lcxxg_tmp6 and .Lcxxg_tmp7
	.uleb128 .Lcxxg_tmp8-.Lcxxg_func_begin0           //     jumps to .Lcxxg_tmp8
	.byte	1                               //   On action: 1
	.uleb128 .Lcxxg_tmp7-.Lcxxg_func_begin0           // >> Call Site 6 <<
	.uleb128 .Lcxxg_func_end0-.Lcxxg_tmp7             //   Call between .Lcxxg_tmp7 and .Lcxxg_func_end0
	.byte	0                               //     has no landing pad
	.byte	0                               //   On action: cleanup
.Lcxxg_cst_end0:
	.byte	1                               // >> Action Record 1 <<
                                        //   Catch TypeInfo 1
	.byte	0                               //   No further actions
	.byte	2                               // >> Action Record 2 <<
                                        //   Catch TypeInfo 2
	.byte	125                             //   Continue to action 1
	.p2align	2, 0x0
                                        // >> Catch TypeInfos <<
.Lcxxg_tmp12:                                // TypeInfo 2
	.xword	.Lcxxg__ZTISt9exception.DW.stub-.Lcxxg_tmp12
	.xword	0                               // TypeInfo 1
.Lcxxg_ttbase0:
	.p2align	2, 0x0
                                        // -- End function
	.section	.text.cxxg_call_terminate,"axG",@progbits,cxxg_call_terminate,comdat
	.hidden	cxxg_call_terminate          // -- Begin function cxxg_call_terminate
	.weak	cxxg_call_terminate
	.p2align	2
	.type	cxxg_call_terminate,@function
cxxg_call_terminate:                 // @cxxg_call_terminate
	.cfi_startproc
// %bb.0:
	stp	x29, x30, [sp, #-16]!           // 16-byte Folded Spill
	.cfi_def_cfa_offset 16
	mov	x29, sp
	.cfi_def_cfa w29, 16
	.cfi_offset w30, -8
	.cfi_offset w29, -16
	bl	__cxa_begin_catch
	bl	_ZSt9terminatev
.Lcxxg_func_end1:
	.size	cxxg_call_terminate, .Lcxxg_func_end1-cxxg_call_terminate
	.cfi_endproc
                                        // -- End function
	.text
	.hidden	cadence_install_terminate_handler // -- Begin function cadence_install_terminate_handler
	.globl	cadence_install_terminate_handler
	.p2align	2
	.type	cadence_install_terminate_handler,@function
cadence_install_terminate_handler:      // @cadence_install_terminate_handler
	.cfi_startproc
// %bb.0:
	stp	x29, x30, [sp, #-16]!           // 16-byte Folded Spill
	.cfi_def_cfa_offset 16
	mov	x29, sp
	.cfi_def_cfa w29, 16
	.cfi_offset w30, -8
	.cfi_offset w29, -16
	adrp	x0, _ZL25cadence_terminate_handlerv
	add	x0, x0, :lo12:_ZL25cadence_terminate_handlerv
	bl	_ZSt13set_terminatePFvvE
	adrp	x8, _ZL6g_prev
	str	x0, [x8, :lo12:_ZL6g_prev]
	.cfi_def_cfa wsp, 16
	ldp	x29, x30, [sp], #16             // 16-byte Folded Reload
	.cfi_def_cfa_offset 0
	.cfi_restore w30
	.cfi_restore w29
	ret
.Lcxxg_func_end2:
	.size	cadence_install_terminate_handler, .Lcxxg_func_end2-cadence_install_terminate_handler
	.cfi_endproc
                                        // -- End function
	.p2align	2                               // -- Begin function _ZL25cadence_terminate_handlerv
	.type	_ZL25cadence_terminate_handlerv,@function
_ZL25cadence_terminate_handlerv:        // @_ZL25cadence_terminate_handlerv
.Lcxxg_func_begin1:
	.cfi_startproc
	.cfi_personality 156, cxxg_ref_gxx_personality
	.cfi_lsda 28, .Lcxxg_exception1
// %bb.0:
	sub	sp, sp, #368
	.cfi_def_cfa_offset 368
	stp	x29, x30, [sp, #320]            // 16-byte Folded Spill
	stp	x28, x21, [sp, #336]            // 16-byte Folded Spill
	stp	x20, x19, [sp, #352]            // 16-byte Folded Spill
	add	x29, sp, #320
	.cfi_def_cfa w29, 48
	.cfi_offset w19, -8
	.cfi_offset w20, -16
	.cfi_offset w21, -24
	.cfi_offset w28, -32
	.cfi_offset w30, -40
	.cfi_offset w29, -48
	adrp	x8, _ZL6g_busy
	ldr	w9, [x8, :lo12:_ZL6g_busy]
	cbnz	w9, .Lcxxg_BB3_22
// %bb.1:
	mov	w9, #1                          // =0x1
	str	w9, [x8, :lo12:_ZL6g_busy]
	mov	x8, sp
	strb	wzr, [sp]
	add	x19, x8, #96
	strb	wzr, [sp, #96]
	bl	__cxa_current_exception_type
	cbz	x0, .Lcxxg_BB3_21
// %bb.2:
.Lcxxg_tmp9:
	mov	x20, x0
	bl	__cxa_rethrow
.Lcxxg_tmp10:
// %bb.3:
.Lcxxg_BB3_4:
.Lcxxg_tmp11:
	mov	x21, x1
	bl	__cxa_begin_catch
	cmp	w21, #2
	b.ne	.Lcxxg_BB3_7
// %bb.5:
	ldr	x8, [x0]
	ldur	x9, [x8, #-8]
	ldr	x10, [x9, #8]
	cbnz	x10, .Lcxxg_BB3_9
// %bb.6:
	mov	x9, xzr
	b	.Lcxxg_BB3_12
.Lcxxg_BB3_7:
	ldr	x9, [x20, #8]
	cbnz	x9, .Lcxxg_BB3_16
// %bb.8:
	mov	x8, xzr
	b	.Lcxxg_BB3_19
.Lcxxg_BB3_9:
	mov	x9, xzr
	mov	x11, sp
.Lcxxg_BB3_10:                               // =>This Inner Loop Header: Depth=1
	ldrb	w12, [x10, x9]
	cbz	w12, .Lcxxg_BB3_12
// %bb.11:                              //   in Loop: Header=BB3_10 Depth=1
	strb	w12, [x11, x9]
	add	x9, x9, #1
	cmp	x9, #95
	b.ne	.Lcxxg_BB3_10
.Lcxxg_BB3_12:
	mov	x10, sp
	strb	wzr, [x10, x9]
	ldr	x8, [x8, #16]
	blr	x8
	mov	x8, xzr
	cbz	x0, .Lcxxg_BB3_15
.Lcxxg_BB3_13:                               // =>This Inner Loop Header: Depth=1
	ldrb	w9, [x0, x8]
	cbz	w9, .Lcxxg_BB3_15
// %bb.14:                              //   in Loop: Header=BB3_13 Depth=1
	strb	w9, [x19, x8]
	add	x8, x8, #1
	cmp	x8, #223
	b.ne	.Lcxxg_BB3_13
.Lcxxg_BB3_15:
	add	x8, x19, x8
	b	.Lcxxg_BB3_20
.Lcxxg_BB3_16:
	mov	x8, xzr
	mov	x10, sp
.Lcxxg_BB3_17:                               // =>This Inner Loop Header: Depth=1
	ldrb	w11, [x9, x8]
	cbz	w11, .Lcxxg_BB3_19
// %bb.18:                              //   in Loop: Header=BB3_17 Depth=1
	strb	w11, [x10, x8]
	add	x8, x8, #1
	cmp	x8, #95
	b.ne	.Lcxxg_BB3_17
.Lcxxg_BB3_19:
	mov	x9, sp
	add	x8, x9, x8
.Lcxxg_BB3_20:
	strb	wzr, [x8]
	bl	__cxa_end_catch
.Lcxxg_BB3_21:
	mov	x0, sp
	mov	x1, x19
	bl	cadence_terminate_report
.Lcxxg_BB3_22:
	adrp	x8, _ZL6g_prev
	ldr	x8, [x8, :lo12:_ZL6g_prev]
	cbz	x8, .Lcxxg_BB3_24
// %bb.23:
	blr	x8
.Lcxxg_BB3_24:
	bl	abort
.Lcxxg_func_end3:
	.size	_ZL25cadence_terminate_handlerv, .Lcxxg_func_end3-_ZL25cadence_terminate_handlerv
	.cfi_endproc
	.section	.gcc_except_table,"a",@progbits
	.p2align	2, 0x0
cxxg_lsda3:
.Lcxxg_exception1:
	.byte	255                             // @LPStart Encoding = omit
	.byte	156                             // @TType Encoding = indirect pcrel sdata8
	.uleb128 .Lcxxg_ttbase1-.Lcxxg_ttbaseref1
.Lcxxg_ttbaseref1:
	.byte	1                               // Call site Encoding = uleb128
	.uleb128 .Lcxxg_cst_end1-.Lcxxg_cst_begin1
.Lcxxg_cst_begin1:
	.uleb128 .Lcxxg_func_begin1-.Lcxxg_func_begin1    // >> Call Site 1 <<
	.uleb128 .Lcxxg_tmp9-.Lcxxg_func_begin1           //   Call between .Lcxxg_func_begin1 and .Lcxxg_tmp9
	.byte	0                               //     has no landing pad
	.byte	0                               //   On action: cleanup
	.uleb128 .Lcxxg_tmp9-.Lcxxg_func_begin1           // >> Call Site 2 <<
	.uleb128 .Lcxxg_tmp10-.Lcxxg_tmp9                 //   Call between .Lcxxg_tmp9 and .Lcxxg_tmp10
	.uleb128 .Lcxxg_tmp11-.Lcxxg_func_begin1          //     jumps to .Lcxxg_tmp11
	.byte	3                               //   On action: 2
	.uleb128 .Lcxxg_tmp10-.Lcxxg_func_begin1          // >> Call Site 3 <<
	.uleb128 .Lcxxg_func_end3-.Lcxxg_tmp10            //   Call between .Lcxxg_tmp10 and .Lcxxg_func_end3
	.byte	0                               //     has no landing pad
	.byte	0                               //   On action: cleanup
.Lcxxg_cst_end1:
	.byte	1                               // >> Action Record 1 <<
                                        //   Catch TypeInfo 1
	.byte	0                               //   No further actions
	.byte	2                               // >> Action Record 2 <<
                                        //   Catch TypeInfo 2
	.byte	125                             //   Continue to action 1
	.p2align	2, 0x0
                                        // >> Catch TypeInfos <<
.Lcxxg_tmp13:                                // TypeInfo 2
	.xword	.Lcxxg__ZTISt9exception.DW.stub-.Lcxxg_tmp13
	.xword	0                               // TypeInfo 1
.Lcxxg_ttbase1:
	.p2align	2, 0x0
                                        // -- End function
	.type	_ZL6g_prev,@object              // @_ZL6g_prev
	.local	_ZL6g_prev
	.comm	_ZL6g_prev,8,8
	.type	_ZL6g_busy,@object              // @_ZL6g_busy
	.local	_ZL6g_busy
	.comm	_ZL6g_busy,4,4
	.data
	.p2align	3, 0x0
.Lcxxg__ZTISt9exception.DW.stub:
	.xword	_ZTISt9exception
	.hidden	cxxg_ref_gxx_personality
	.weak	cxxg_ref_gxx_personality
	.section	.data.cxxg_ref_gxx_personality,"aGw",@progbits,cxxg_ref_gxx_personality,comdat
	.p2align	3, 0x0
	.type	cxxg_ref_gxx_personality,@object
	.size	cxxg_ref_gxx_personality, 8
cxxg_ref_gxx_personality:
	.xword	__gxx_personality_v0
	.section	".note.GNU-stack","",@progbits
