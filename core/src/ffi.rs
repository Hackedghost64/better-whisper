use std::ffi::{CStr, CString};
use std::os::raw::c_char;

use crate::cleaner::{CleanerOptions, FlowCleaner};
use crate::dictionary::CustomDictionary;

/// C ABI: Cleans transcribed text (strips fillers, fixes stutters, handles context).
/// The returned string must be freed with `better_whisper_free_string`.
#[no_mangle]
pub unsafe extern "C" fn better_whisper_clean_text(
    input: *const c_char,
    context_before_cursor: *const c_char,
    remove_fillers: bool,
    deduplicate_stutters: bool,
    handle_self_corrections: bool,
) -> *mut c_char {
    if input.is_null() {
        return std::ptr::null_mut();
    }

    let c_str = match CStr::from_ptr(input).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let context = if !context_before_cursor.is_null() {
        CStr::from_ptr(context_before_cursor).to_str().ok().map(|s| s.to_string())
    } else {
        None
    };

    let options = CleanerOptions {
        remove_fillers,
        deduplicate_stutters,
        handle_self_corrections,
        context_before_cursor: context,
    };

    let cleaned = FlowCleaner::clean_text(c_str, &options);

    // Apply default custom dictionary replacements
    let dict = CustomDictionary::new();
    let final_text = dict.apply(&cleaned);

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
                true,
                true,
                true,
            );
            assert!(!ptr.is_null());
            let result = CStr::from_ptr(ptr).to_str().unwrap();
            assert_eq!(result, "I like Phonon-2");
            better_whisper_free_string(ptr);
        }
    }
}
