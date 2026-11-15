# Bloom roadmap

Every feature the user has asked for, so nothing gets dropped between sessions.
Status: `[x]` done and verified on device · `[~]` built but not verified / partial · `[ ]` not started.
Update this file whenever something changes status.

## 1. Root backend (real account)
- [x] Sign in with username/password via `connect.rootapp.com` (user solves Turnstile in a WebView)
- [x] Session saved; API calls go to `web_api_url` (api.rootapp.com)
- [x] Communities and channels (categories, unread dots, correct channel types)
- [x] Messages load with correct authors and times (GUID epoch 2020-01-01), system events, attachments, link previews, replies, mentions, emoji (verified live)
- [~] Send / edit / delete / react (wired, not verified)
- [x] Friends list + DMs on the Home screen (28 friends / 34 DMs verified live)
- [x] Live updates over the notifications WebSocket: messages, edits, deletes, reactions, typing, presence, DM list (verified live); polling only as fallback
- [x] Real profile pictures, banners and server icons via Asset.Get (verified live)
- [x] Typing indicators (send + receive), replies with parent snippets, mentions from real data
- [x] Raw response dump turned off and deleted

## 2. Composer and media
- [~] `+` button on the message pill opens an attachment sheet with four options + recent photos grid:
      Photos & library, GIFs, Camera, Files
- [~] GIFs: search/trending via Klipy (user's own key, Settings → GIFs), Root favourites, animated in chat
- [~] Emoji: panel (unicode by category + custom/animated, search), shortcodes, custom emoji in sent messages; custom emoji show as :name: inside the text box (rendered in the preview line above it)
      (unicode + community custom emoji)
- [~] Preview tray before sending (tap to view, × to remove)
- [~] Tap a sent image to open it full screen (pinch/double-tap zoom, drag to dismiss)
- [~] In-app video player: volume, playback speed; tapping empty space hides the controls,
      tapping anywhere brings them back, and they also come back when the video ends
- [~] Voice messages by holding the send button (record, slide to cancel, playback in chat)

## 2b. Chat interactions (added 2026-11-14)
- [~] Server banner: show the community's real banner image at the top of the channel list
- [~] Swipe a message to reply, with an animated reply icon that morphs and spins in as you drag
- [~] Tap a reply preview to jump to the original message (scroll to it and highlight it)

## 3. Profile
- [~] Working Edit Profile that edits everything: picture, banner, username, bio/description,
      custom status (and anything else Root's profile supports)
- [~] Status shows in full on profiles (not cropped)
- [~] Mutual servers / mutual friends on profiles (wired to Root's GetConnectionBetween; spinner bug fixed, not verified)

- [~] Mentions feed (live mentions + replies to you), tap to jump

## 4. Voice channels
- [~] Join / leave voice channels (Root's Cloudflare SFU: ICE verified live; join/publish/subscribe not yet tested in a real call)
- [~] Mute, deafen (per-user volume not yet)
- [~] Talking indicator (audio levels from WebRTC stats, glowing ring)
- [~] Camera
- [~] Screen share (MediaProjection + foreground service)
- [~] Voice channel text chat (chat below the voice stage)
- [~] Participants list from live voice packets

## 5. Looks and layouts
- [~] Three separate layouts and animation styles: Material You (rail + sliding chat, springs), Discord-style (rail + sliding chat + bottom bar Servers/Mentions/You, crisp), Root-style (5 bottom tabs Home/Messages/Friends/Notifications/Profile, community drawer, pushed chat, Root's 150-300 ms timings)
      (match layout and behaviour; no Discord/Root logos, artwork or fonts)
- [x] Material You living shapes: slowly rotate and morph; still rounded square when selected
- [x] Background shapes with sliders (count, rotate speed, morph speed, blur, opacity)
- [x] Customise every colour role per look, in collapsible Settings sections
- [x] Login: logo, floating "Password" label, gray per-character shapes, morphing eye, morph reveal
- [ ] Panels/backgrounds consistent across all screens (audit after each layout change)

## 6. Plugins (Vencord-style)
- [x] 18 plugins (Message Logger, Clear URLs, Text Replace, Message Tags, More Kaomoji, Send Timestamps, ...)
- [ ] More plugins the user picks from Vencord's list

## 8. Feedback round 2026-11-15
- [x] Fast startup: cold start 183 ms to first frame, cached servers/channels/icons visible at 1.5 s (was ~10 s); background refresh; image disk cache with stable keys
- [~] Sending media: attachments now linked in the text like Root's own messages; errors shown as toasts (needs a real send to confirm)
- [~] Scroll back through older messages (loads 50 more near the top)
- [x] Channels in the same order as Root (category order, then channel order)
- [~] DM list: live updates; opening a DM/channel marks it read on Root (SetViewTime)
- [~] Reply to more than one message at once (swipe or long-press several)
- [~] Messages you're replying to are highlighted in the chat
- [~] Swipe-to-reply mirrored (swipe right-to-left, icon on the right)
- [~] Swiping a message must not move the message above it (icon is now an overlay)
- [~] Top bar: feathered (fading) blur, not a dark box
- [~] Opening Settings brings it to the front automatically
- [~] Chat settings: 12-hour time, text size, compact mode, swipe-to-reply, GIF autoplay
- [~] Plugin icon glow not cropped

## 9. Feedback round 2 (2026-11-15)
- [~] Save videos (HLS → MP4 via Media3 Transformer) and images to the gallery
- [~] Playback speed: one menu button (0.25×–2×), controls stay while the menu is open
- [~] Videos/images in chat keep their real aspect ratio (sizes from Root's asset data, verified live for 102/183)
- [~] Video player fits (letterboxes) instead of stretching
- [~] Status dot on profile avatars not cropped
- [~] Profile sheet handle drags the sheet up (expand) and down (collapse/close)
- [~] Mutual servers show server icons
- [~] "Jump to latest" button when scrolled up in chat
- [x] Bots: BOT badge; pictures load (4/4 verified live; they use plain https URLs)
- [~] GIFs no longer shown twice
- [~] Sending: proper Root GUIDs for idempotency ids (random ids were likely rejected), empty content omitted
- [~] Emoji: unicode sent as Root emoji links; custom emoji shown as images inside the message box
- [~] Sending media: matches Root's own media posts (files only in the attachment list)
- [~] Custom emoji load fast (served from cache, images prefetched)

## 10. Feedback round 3 (2026-11-15)
- [~] Sent media shows in Bloom right away (own message replaced by the server copy with attachments)
- [~] Upload skeleton with a live progress bar for sent media
- [~] Custom emoji send correctly (referenced alongside the message, like Root's own client)
- [~] Keyboard button in the emoji panel brings the keyboard back
- [~] Typing indicator: typers' pictures inside morphing shapes, several at once; style chosen in Settings
- [~] Chat window background (gradient or your own image with dim/blur)
- [~] Cleaner reply preview (curved connector, small avatar, coloured name, muted one-line snippet)
- [~] Plugin icons: soft rounded per-plugin icons, glow no longer cropped
- [x] Notifications page from Root (101 loaded live, previews 100/101): mentions + friend requests with accept/decline
- [~] Add friend by username, remove friend, new group DM, add people to a DM
- [ ] Remaining missing settings to audit against Root (community settings, notification preferences per server, blocking, privacy)

## 11. Feedback round 4 (2026-11-15)
- [~] Custom emoji: fixed double-wrapped links ("[[:name:](…") that Root rejected with error 300
- [~] Upload skeleton uses the photo's/video's real aspect ratio
- [~] Sent media stays visible (send returns the server copy with attachments)
- [~] No false "(edited)" (Root stamps edited_at when it finishes processing; only edits >10 s after sending count)
- [~] Swipe right in chat opens the side menu again (reply swipe only claims right-to-left drags)
- [~] Visible chat background: stronger gradient (default) + new pattern option
- [~] React with any emoji (full emoji panel from the long-press menu); reactions sent as Root emoji codes
- [~] Messages move up above the composer when the reply bar / attachment tray appear
- [~] Setting to show/hide channel icons
- [~] Home chips scroll horizontally (no squashed "New group", no gap above the DM list)

## 12. Feedback round 5 (2026-11-15)
- [x] Custom emoji sending: Root rejected the emoji/mention references Bloom added to attachment_token_uris (error 300). Root's client only lists uploaded files there; Root derives emoji from the text. Verified format on real messages: `[:name:](root://emoji/ID)`
- [x] Standard emoji: Root writes `[:sob:](root://emoji/:sob:)` (verified on real DMs). Bloom now shows these as emoji (was raw ":pensive:") and sends the same format
- [x] Reactions: loaded from message history (field 15, was never read); sent as ":heart:" (verified format) or the custom emoji id; custom reactions show their image
- [x] Emoji panel lists only emoji you can use (Root ListMine), not every emoji seen in chats
- [~] Reaction animations (pop in, bounce on tap, rolling count)
- [~] Keyboard no longer pushes messages out of view (insets were counted twice)
- [~] Notifications: actor/channel/message decoded from the payload (verified: 101 notifications, 17 distinct people, 0 showing yourself, 100 with a target); tap opens the chat and highlights the message
- [~] Image viewer: top+bottom vignette, tap to hide/show controls; images of unknown size keep their natural shape

## 13. Feedback round 6 (2026-11-15)
- [x] Custom emoji reactions use Root's ":name:ID:" code (seen on a real reaction): shown as the emoji image, sent in the same form
- [x] Root's JoyPixels-style emoji names (e.g. :woman_in_lotus_position_tone2:) resolve via Unicode names + aliases (unit tests)
- [x] "Software caused connection abort (os error 103)" after returning to the app: stale pooled connection; requests retry on a fresh one
- [~] Message notifications (Android): DMs / mentions / replies / @everyone / all server messages, each toggleable
- [~] Notification content styles: picture + message, message only, only who pinged you and where, private; server/channel line toggle
- [~] Tap a notification → opens the chat and highlights the message; notification cleared when opened
- [~] Optional "Stay connected in background" (quiet foreground service) so notifications keep arriving
- [ ] Push notifications when Bloom is fully closed (would need Root's push service; not available to third-party apps)

## 14. Feedback round 7 (2026-11-15)
- [~] Messages that mention you, reply to you or ping @everyone are highlighted (tint + accent bar)
- [~] Replying to several messages shows every replied-to message (Root's parents list; was first only)
- [~] Sent photos keep their real shape: decoded image size wins over Root's stored size (which ignores camera rotation)
- [~] Long-press a friend or DM: nickname (local), per-person notifications (default / every message / only mentions / nothing), mute 15m/30m/1h/6h/until turned back on, pin to top, message, profile, copy username, remove friend
- [~] Pin DMs plugin actually implemented (pinned first, pin icon); muted icon in DM list
- [~] Logs & crashes (Settings): failed Root requests, core errors, core panics (no longer crash the app), app crashes with stack traces; copy / share / clear

## 15. Channel icons like Root (2026-11-15)
- [x] Channel pictures from Root (icon_asset_uri; live: 116 of 167 channels have one, 115 have a description)
- [~] Rows: picture in a rounded square + small type badge (text/app/voice) + description line; fallback symbol tile
- [~] Settings → Chat: "Channel icons" (pictures vs plain # symbols), "Channel descriptions"

## 16. Feedback round 8 (2026-11-15)
- [~] @mentions: picker while typing "@" (people from this chat, friends, everyone known; @All/@Here; server roles from Root, live: 53/2/5 mentionable); sent as Root mention links (id format verified against a real message)
- [x] Bots: GUID type 9 now applied to every user source (snapshot + unresolved authors); stronger BOT pill badge (chat, profile, friends, @ picker)
- [~] Profile actions: Add friend · Accept/Decline (incoming request) · Message + Call (friends)
- [~] Markdown everywhere (chat + bios): bold, italic, underline, strike, ||spoiler|| (tap to reveal), headings, quotes, -# subtext, code; bios rendered like messages (emoji, mentions)
- [x] DM list previews: stale snapshot + no live updates was the cause (Root's own preview matched the newest message 14/15); previews now update from live and fetched messages
- [~] Mentions drawn as soft fully-rounded pills; tapping @name opens the profile
- [~] New messages: fade + short rise (no scaling); list glides; options Rise / Blur in / Fade / None
- [~] Reactions: smooth expand/fade in and out, rolling count, outline when it's yours
- [~] Media: no placeholder ratio; sent media takes the picked file's shape immediately; unknown shapes appear at their own size
- [~] Customize (Mochy-inspired, GPL project, ideas only): animations on/off + speed 0.5x–2x, message entrance style, element blur, frosted glass + strength, corner rounding, hide status bar, live preview; Settings search

- [~] Start-of-chat intro (DM: picture/name; group; channel: picture, welcome, description)
- [~] Date dividers between days (Today / Yesterday / Month day[, year])
- [~] Reopening the app returns to the last open chat
- [~] Opening any chat starts at the newest message

## 17. Customize, now playing, fonts, emoji, performance, release (2026-11-15)
- [x] Bloom is GPL-3.0 (user's choice) so Mochy Music's customise components could be ported, with credit
- [~] Customize screen (ported from Mochy): corner rounding card + slider, animation on/off + speed, message entrance pager, living icons, blur, fonts, emoji packs, now playing, screen, performance; search
- [~] Fonts: system, 10 Google Fonts (downloaded once, variable weights), import TTF/OTF; whole app or messages only; weight, letter spacing
- [~] Emoji packs (runtime images, nothing bundled): Apple, Windows 11 Fluent (1521 mapped), Google, Twitter, Facebook; System (= Samsung on Samsung phones)
- [~] Now playing: media-session listener (notification access), song hidden as zero-width characters in Root status; only Bloom decodes it; profile card + friends list
- [~] Performance: icons stop requesting frames when idle ("only the open one" default), background shapes at 1/4 resolution, battery saver steps effects down and stops forcing 120 Hz, profileinstaller + speed-profile compile
- [x] Release key outside the repo (~/.bloom-signing); phone migrated to it with data kept
- [x] Screenshots from a demo-data copy (no personal data); smoothness measured with gfxinfo: scrolling median 5 ms/frame, 99th pct 11–12 ms, ~2.7% janky (first frames of a swipe); idle redraws 120/s → 24/s (rotating glows only tick while visible, at 30 Hz; background shapes 30 Hz); window asks the system not to lower its refresh rate (API 35+) except in battery saver
- [x] Crash fixed: starting a call without microphone permission (permissions reset after the key switch) crashed the voice service; now asks for the mic first and never claims permissions it doesn't have
- [x] Published: github.com/vsoultann/bloom (single clean commit), release v0.2.0 with APK

## Known bugs to check
- [ ] Password field possibly cleared after tapping the eye (seen twice while the user was also using the phone)
- [ ] Tapping a channel once opened a message author's profile instead
- [ ] "Members" count shows only the signed-in user (now shows channel count instead; not installed yet)
