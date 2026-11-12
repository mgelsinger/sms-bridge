//! How the app tells the user about new texts: a dot on its taskbar icon while
//! anything is unread, and pop-up notifications only if the user asked for them.

/// Whether an inbound message raises a pop-up notification. Pop-ups are off unless
/// the user turned them on, and the conversation on screen in a focused window
/// never raises one.
pub fn wants_popup(
    popups_enabled: bool,
    window_focused: bool,
    open_thread: Option<u64>,
    message_thread: u64,
) -> bool {
    popups_enabled && !(window_focused && open_thread == Some(message_thread))
}

/// The tray tooltip, which also covers the case where the window is hidden and
/// has no taskbar icon to put a dot on.
pub fn tray_tooltip(unread: u32) -> String {
    match unread {
        0 => "SMS Bridge".to_owned(),
        1 => "SMS Bridge - 1 unread text".to_owned(),
        n => format!("SMS Bridge - {n} unread texts"),
    }
}

/// A filled red circle as RGBA pixels, `size` by `size`, for the taskbar overlay
/// icon. Edge pixels are blended so the dot stays round at 16 pixels.
pub fn unread_dot_rgba(size: u32) -> Vec<u8> {
    const RED: [u8; 3] = [0xE5, 0x39, 0x35];
    let mut pixels = Vec::with_capacity((size * size * 4) as usize);
    let centre = f64::from(size) / 2.0;
    let radius = centre - 0.5;
    for y in 0..size {
        for x in 0..size {
            let dx = f64::from(x) + 0.5 - centre;
            let dy = f64::from(y) + 0.5 - centre;
            // 1 inside the circle, 0 outside, a ramp across the one-pixel edge.
            let coverage = (radius + 0.5 - (dx * dx + dy * dy).sqrt()).clamp(0.0, 1.0);
            pixels.extend_from_slice(&RED);
            pixels.push((coverage * 255.0).round() as u8);
        }
    }
    pixels
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn popups_are_off_unless_asked_for() {
        assert!(!wants_popup(false, false, None, 7));
        assert!(!wants_popup(false, true, Some(7), 7));
        assert!(wants_popup(true, false, None, 7));
        assert!(wants_popup(true, false, Some(7), 7), "window not focused");
        assert!(
            wants_popup(true, true, Some(8), 7),
            "another thread is open"
        );
        assert!(
            !wants_popup(true, true, Some(7), 7),
            "the user is looking at it"
        );
    }

    #[test]
    fn tooltip_counts_unread_texts() {
        assert_eq!(tray_tooltip(0), "SMS Bridge");
        assert_eq!(tray_tooltip(1), "SMS Bridge - 1 unread text");
        assert_eq!(tray_tooltip(12), "SMS Bridge - 12 unread texts");
    }

    #[test]
    fn the_dot_is_a_filled_circle_with_clear_corners() {
        for size in [16u32, 32] {
            let px = unread_dot_rgba(size);
            assert_eq!(px.len(), (size * size * 4) as usize);
            let alpha = |x: u32, y: u32| px[((y * size + x) * 4 + 3) as usize];
            assert_eq!(alpha(size / 2, size / 2), 255, "centre is solid");
            assert_eq!(alpha(0, 0), 0, "corner is clear");
            assert_eq!(alpha(size - 1, size - 1), 0, "corner is clear");
            assert!(alpha(size / 2, 0) > 0, "the circle reaches the top edge");
        }
    }
}
