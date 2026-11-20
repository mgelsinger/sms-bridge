//! Naming MMS attachments saved to disk. Pure, so it is tested on any OS.

use std::path::{Path, PathBuf};

/// The usual extension for the content types phones send, without the dot.
fn extension_for(content_type: &str) -> Option<&'static str> {
    let ct = content_type.split(';').next().unwrap_or("").trim().to_ascii_lowercase();
    Some(match ct.as_str() {
        "image/jpeg" | "image/jpg" => "jpg",
        "image/png" => "png",
        "image/gif" => "gif",
        "image/webp" => "webp",
        "image/heic" => "heic",
        "image/heif" => "heif",
        "image/bmp" => "bmp",
        "video/mp4" => "mp4",
        "video/3gpp" => "3gp",
        "video/3gpp2" => "3g2",
        "video/quicktime" => "mov",
        "video/webm" => "webm",
        "audio/amr" => "amr",
        "audio/aac" => "aac",
        "audio/mp4" | "audio/m4a" | "audio/x-m4a" => "m4a",
        "audio/mpeg" | "audio/mp3" => "mp3",
        "audio/ogg" => "ogg",
        "audio/wav" | "audio/x-wav" => "wav",
        "text/x-vcard" | "text/vcard" => "vcf",
        "text/x-vcalendar" | "text/calendar" => "ics",
        "text/plain" => "txt",
        "application/pdf" => "pdf",
        _ => return None,
    })
}

/// A safe file name for an attachment: the sender's name for it with anything Windows
/// refuses replaced, or `attachment-<part id>`, with an extension that fits the type.
pub fn file_name(name: Option<&str>, content_type: &str, part_id: u64) -> String {
    let cleaned: String = name
        .unwrap_or("")
        .chars()
        .map(|c| if c.is_control() || r#"<>:"/\|?*"#.contains(c) { '_' } else { c })
        .collect();
    let cleaned = cleaned.trim().trim_matches('.').trim();
    let stem_is_reserved = {
        let stem = cleaned.split('.').next().unwrap_or("").to_ascii_uppercase();
        matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
            || ((stem.starts_with("COM") || stem.starts_with("LPT")) && stem.len() == 4 && stem.as_bytes()[3].is_ascii_digit())
    };
    let mut base = if cleaned.is_empty() || stem_is_reserved {
        format!("attachment-{part_id}")
    } else {
        cleaned.chars().take(120).collect()
    };
    if let Some(ext) = extension_for(content_type) {
        let has_ext = Path::new(&base)
            .extension()
            .and_then(|e| e.to_str())
            .is_some_and(|e| !e.is_empty() && e.len() <= 5);
        if !has_ext {
            base.push('.');
            base.push_str(ext);
        }
    }
    base
}

/// `dir/name`, or `dir/stem (2).ext` and so on if that is taken, the way Windows names copies.
pub fn unique_path(dir: &Path, name: &str, exists: impl Fn(&Path) -> bool) -> PathBuf {
    let first = dir.join(name);
    if !exists(&first) {
        return first;
    }
    let p = Path::new(name);
    let stem = p.file_stem().and_then(|s| s.to_str()).unwrap_or(name);
    let ext = p.extension().and_then(|e| e.to_str());
    for n in 2..10_000 {
        let candidate = match ext {
            Some(e) => dir.join(format!("{stem} ({n}).{e}")),
            None => dir.join(format!("{stem} ({n})")),
        };
        if !exists(&candidate) {
            return candidate;
        }
    }
    first
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn names_come_from_the_part_with_a_fitting_extension() {
        assert_eq!(file_name(Some("IMG_2041.jpg"), "image/jpeg", 7), "IMG_2041.jpg");
        assert_eq!(file_name(Some("image000000"), "image/jpeg", 7), "image000000.jpg");
        assert_eq!(file_name(None, "video/mp4", 7), "attachment-7.mp4");
        assert_eq!(file_name(Some("  "), "audio/amr", 9), "attachment-9.amr");
        assert_eq!(file_name(None, "application/x-unknown", 3), "attachment-3");
        assert_eq!(file_name(Some("Alex.vcf"), "text/x-vcard; charset=utf-8", 3), "Alex.vcf");
    }

    #[test]
    fn names_windows_would_refuse_are_made_safe() {
        assert_eq!(file_name(Some(r#"a/b\c:d*e?f"g<h>i|j.png"#), "image/png", 1), "a_b_c_d_e_f_g_h_i_j.png");
        assert_eq!(file_name(Some("..\\..\\boot.ini"), "text/plain", 1), "_.._boot.ini");
        assert_eq!(file_name(Some("CON.jpg"), "image/jpeg", 4), "attachment-4.jpg");
        assert_eq!(file_name(Some("com1"), "image/jpeg", 4), "attachment-4.jpg");
        assert_eq!(file_name(Some("..."), "image/jpeg", 4), "attachment-4.jpg");
        let long = "x".repeat(300);
        assert_eq!(file_name(Some(&long), "image/png", 1).len(), 124);
    }

    #[test]
    fn an_existing_file_is_never_overwritten() {
        let dir = Path::new("downloads");
        let taken: HashSet<PathBuf> = [dir.join("a.jpg"), dir.join("a (2).jpg")].into_iter().collect();
        assert_eq!(unique_path(dir, "a.jpg", |p| taken.contains(p)), dir.join("a (3).jpg"));
        assert_eq!(unique_path(dir, "b.jpg", |p| taken.contains(p)), dir.join("b.jpg"));
        let taken: HashSet<PathBuf> = [dir.join("notes")].into_iter().collect();
        assert_eq!(unique_path(dir, "notes", |p| taken.contains(p)), dir.join("notes (2)"));
    }
}
