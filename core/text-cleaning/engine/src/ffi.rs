use std::ffi::{CStr, CString};
use std::os::raw::c_char;

use lazy_static::lazy_static;

use crate::cleaner::{CleanerOptions, FlowCleaner};
use crate::dictionary::CustomDictionary;

lazy_static! {
    /// Built once per process. Building it per call recompiled every vocabulary
    /// regex and dominated the cleaner's latency.
    static ref DEFAULT_DICTIONARY: CustomDictionary = CustomDictionary::new();
}

/// C ABI: Cleans transcribed text (strips fillers, fixes stutters, handles context).
/// The returned string must be freed with `better_whisper_free_string`.
///
/// Booleans are passed as `int32_t` so the ABI is unambiguous across C/C++/JNI.
#[no_mangle]
pub unsafe extern "C" fn better_whisper_clean_text(
    input: *const c_char,
    context_before_cursor: *const c_char,
    remove_fillers: i32,
    deduplicate_stutters: i32,
    handle_self_corrections: i32,
    aggressive_discourse: i32,
) -> *mut c_char {
    if input.is_null() {
        return std::ptr::null_mut();
    }

    let c_str = match CStr::from_ptr(input).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let context = if !context_before_cursor.is_null() {
        CStr::from_ptr(context_before_cursor)
            .to_str()
            .ok()
            .map(|s| s.to_string())
    } else {
        None
    };

    let options = CleanerOptions {
        remove_fillers: remove_fillers != 0,
        deduplicate_stutters: deduplicate_stutters != 0,
        handle_self_corrections: handle_self_corrections != 0,
        aggressive_discourse: aggressive_discourse != 0,
        context_before_cursor: context,
    };

    let cleaned = FlowCleaner::clean_text(c_str, &options);
    let final_text = DEFAULT_DICTIONARY.apply(&cleaned);

    match CString::new(final_text) {
        Ok(c_string) => c_string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C ABI: Frees a string allocated by `better_whisper_clean_text`.
#[no_mangle]
pub unsafe extern "C" fn better_whisper_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(CString::from_raw(ptr));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ffi_clean_text_roundtrip() {
        unsafe {
            let input = CString::new("Um, I I like phonon 2").unwrap();
            let ptr = better_whisper_clean_text(
                input.as_ptr(),
                std::ptr::null(),
                1,
                1,
                1,
                1,
            );
            assert!(!ptr.is_null());
            let result = CStr::from_ptr(ptr).to_str().unwrap();
            assert_eq!(result, "I like Phonon-2");
            better_whisper_free_string(ptr);
        }
    }

    #[test]
    fn test_ffi_null_input() {
        unsafe {
            let ptr = better_whisper_clean_text(
                std::ptr::null(),
                std::ptr::null(),
                1,
                1,
                1,
                1,
            );
            assert!(ptr.is_null());
        }
    }

    #[test]
    fn test_ffi_flags_are_honoured() {
        unsafe {
            let input = CString::new("Um, it's basically fine").unwrap();
            let ptr = better_whisper_clean_text(
                input.as_ptr(),
                std::ptr::null(),
                0, // do not remove fillers
                0,
                0,
                1,
            );
            assert!(!ptr.is_null());
            let result = CStr::from_ptr(ptr).to_str().unwrap();
            assert_eq!(result, "Um, it's basically fine");
            better_whisper_free_string(ptr);
        }
    }
}
