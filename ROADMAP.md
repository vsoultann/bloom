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

## 18. Feedback round 9 (2026-11-15)
- [x] Call crash: was the mic-permission crash after the key switch (fixed); calls now ask for the mic first
- [~] Call button in DMs/groups (phone icon; red hang-up while in the call) + call stage shown in the DM
- [x] Voice messages: Root's format is voice-message-<ms>-<48 hex peaks>.m4a (from Root's encodeVoiceMessageFileName); Bloom now records the waveform and uses it; received ones play with a waveform player
- [x] Attachments carry their real file name/MIME/size (MessageUri.attachment, live: 175/176 named) — voice messages and audio recognised (old one now plays as audio, not a silent video)
- [x] Hold-to-record rewritten as one gesture (release used to be lost); mic button turns into a living, voice-reactive Material shape while recording
- [~] Top bar: true progressive blur + vibrancy (saturation) + light tint + grain instead of a dark slab; bars/composer same treatment
- [x] Living icons default "always" (user had "off" saved; restored to always at their request)
- [x] Roles: Roles page from the server header (live: 55/43/29 roles with counts), Manage roles (create / edit name, colour, mentionable, self-assign / delete; edits resend Root's permissions byte-for-byte), member roles on profiles with add/remove; white/black = "no colour"
- [~] You page (Mochy profile page port, GPL-3.0): dot-grid header with giant drifting BLOOM, greeting, ID card with marquee strip and join date from the Root id, pull-down stretch/tilt/flip to stats, big-number cards, version capsule with GitHub update check, settings rows; opened from your avatar on the rail
- [ ] Role create/edit/delete and calls not exercised live (they change things for other people)

## 19. UI/UX audit (2026-11-16)
Design rules written to `docs/DESIGN.md` (motion, shape, spacing, type, contrast, performance).
- [x] Side panel slide no longer recomposes the whole screen every frame (drag read in draw phase; measured 0.69% janky frames, median 7 ms, over 8 slides)
- [~] Profile sheet open/close: background blur + recede done in the layer (was recomposing the app tree every frame)
- [~] Profile sheet handle: pulling up grows the sheet live (did nothing until release); height animated in layout, not composition
- [~] Swipe-to-reply: only the reply icon updates while dragging (the whole message used to recompose per frame)
- [~] Composer: removed double size animation (wobble/lag when reply bar, typing line or tray appear); list stays pinned to the bottom without fighting the animation
- [~] Jump-to-latest button sits above the composer at any height (was fixed 100 dp, covered by reply bar/tray)
- [~] Message highlights (mention, flash, reply, deleted) drawn in one draw call, no recomposition
- [~] Glow effects reuse blur filters (were allocated every frame)
- [~] Every animation follows Customize → animations on/off + speed (≈35 transitions used library defaults; login, You page, Root/Discord tabs, upload bar used raw timings)
- [~] Corners follow the look + rounding slider everywhere (≈25 hard-coded radii: sheets 28/32/36, cards 16–24, dialogs, menus, toasts)
- [~] Top bar frosts the chat background too (hard black edge where no messages were behind it)
- [~] Chat title vertically centred when the channel has no description
- [~] Settings button on the rail has press feedback like the others; DM tab pill moves without recomposing
- [~] Settings → Colors: warns when a custom text colour is hard to read (< 4.5:1) with a one-tap Fix. Current Material overrides: On primary #787877 on Primary #BABABA = ~2:1 (selected tab / Home icon text barely visible)

## 19. Round 10 (2026-11-16)
- [x] Overlapping messages: cached list could be out of order (live system events appended at the end) and the cached→fresh swap glided every item at once; now time-ordered everywhere and no glide on bulk swaps
- [~] DM top bar: their picture with status dot, live subtitle (now playing / status / presence); groups stacked; channels show their picture
- [~] Profile: status as an animated bubble floating out of the avatar (never over the banner; colour/frosted/solid in Customize); bold now-playing card with live equaliser
- [~] Links: accent colour, no hard underline, shortened; underline + highlight while pressed
- [~] Rail: your avatar pinned bottom-left, rail feathers out behind it; tapping flies the picture into the You card (shared-element style)
- [~] Jumbo emoji for emoji-only messages; direction-aware server switch
- [x] Released v0.3.0

## 20. Round 11 (2026-11-16)
- [x] Custom emoji from any server: Root's emoji Resolve answers with a map (id → image), Bloom decoded it wrongly; now resolves, and pairs plain `:Name:` codes with the emoji the message lists (live: 53 rendered, 0 raw left; 73/73 custom reactions with pictures)
- [x] Flag emoji names (:flagca: → 🇨🇦) in bios and messages
- [~] Avatar flight: tracks the landing spot live (it moved with the sliding panel before → flew off-screen); panel closes; source hides while flying
- [~] Status dot no longer cut off (DM top bar, rail avatar weren't supposed to be clipped)
- [x] Fixed a regression from this round: the emoji name matching also matched `:https:` inside already-rendered emoji tokens and printed raw URLs; now skips tokens/URLs (unit test + live check: 53 emoji, 0 raw URLs, 0 broken tokens); bad cached names/messages are cleaned on start
- [~] Avatar flight: drawn from the first frame, curved path, critically damped spring, then a 220 ms cross-fade into the real picture (no clip at the end)
- [x] Released v0.3.1

## 21. Round 12 (2026-11-16)
- [~] Keyboard ⇄ emoji/attach/GIF panels: panels take the keyboard's exact (learned) height; the space under the input is max(keyboard, panel), so swapping never moves the composer; panel waits for the keyboard to fully rise before leaving; inset reads moved to layout/flows (no per-frame recomposition)
- [x] Ghost/overlap after a refresh: leaving items faded out over the new list (and the "settled" flag was a frame late); refresh now merges with kept history, no fade-outs or glides during a swap
- [x] List yanked back down while dragging up: stick-to-bottom keyed on the message count (older history changes it); now keyed on the newest message and never during a drag
- [x] Released v0.3.2

## 22. Round 13 (2026-11-16)
- [~] Keyboard goes down as soon as you swipe out of a chat (drag start) and when the side panel opens
- [~] Panel ⇄ keyboard handoff is continuous: panel grows from the keyboard's actual edge, waits until the keyboard has stopped moving, then shrinks into it (space = max(keyboard, panel)); keyboard height learned from where it settles, not the tallest seen
- [x] Released v0.3.3
- [~] Panels slide down out of frame when closing (and up from below when opening) instead of squashing — v0.3.4

## 23. Calls (2026-11-16)
- [x] Incoming calls: Root's ring packet (123) and "declined" (124) were ignored — now an incoming-call card (ripples, ringtone, vibration, Accept / Decline via DirectMessageGrpcService/RingDecline), call-style notification when Bloom is in the background
- [x] Unknown live events were parsed as "typing", threw, and dropped the whole batch — now parsed one by one, unknown ones ignored
- [x] Call errors were never shown or logged — now a toast and the diagnostics log
- [~] New call stage: live status (connecting / ringing with ripples / timer), living-shape participants that swell with their voice, frosted dock of morphing buttons, tuck-away bar; chat padding follows its height
- [~] Full-screen call view (blurred drifting backdrop of the other person, big living-shape portrait or group grid, your own preview tile, labelled glass dock) + floating call pill anywhere in the app; old in-chat stage now only the pre-join card
- [x] Step-by-step call trace in Logs & crashes (join, ICE servers, session, mic, subscriptions, ICE/connection states, failures)
- [ ] Live call test (joining an empty voice channel) — not run yet: phone was in use
- [~] Now playing: Root rejects the status with error 300 (too long with the hidden part) — fixed in round 25, needs a live check

## 24. Ghost overlap, for real (2026-11-17)
- [x] Opening a chat cleared the list without counting as a swap, so the previous messages faded out in place under the new ones (4h-old messages over new ones, fading video preview). Clearing now bumps the swap epoch, each chat gets its own list (key per chat), and messages never fade out over others (fadeOutSpec null)

## 25. Root 0.9.46 update + audit (2026-11-25)
Root updated on the phone (0.9.46, OTA bundle 2026-11-25); schema re-extracted (`tools/`: 950 messages, 263 RPCs).
- [~] Root moved bios/banners out of user lists (`UserResponse` lost fields 16/17) → profiles now load them from the new `User/Get` (also for yourself); list refreshes keep known bios instead of blanking them
- [~] Root cosmetics: name effects (gradient/colour names in chat, pins, profile — drifting on the profile), profile backgrounds, list of worn cosmetics; live packets 261/262 update them. Unit-tested decoding
- [ ] Cosmetic avatar frames / card borders / name fonts: need Root's art bundles (`UserCosmetic/GetBundlesByIds`, manifest format unverified)
- [~] Pinned messages: overflow ⋮ → Pinned messages (tap to jump, × to unpin); long-press → Pin / Unpin (Root's MessageGrpcService/Pin*)
- [~] Jump to… quick switcher (DMs, groups, channels, communities; unread first)
- [~] Drafts per chat (kept across restarts, "Draft:" in the DM list)
- [~] Frequently used emoji ("Recent" tab, also counts reactions)
- [~] Haptics: send, react, long-press; Customize → Screen → Haptics toggles every haptic in the app
- [~] Per-person call volume (tap someone in a call, 0–200 %, remembered)
- [~] Now playing: denser hidden encoding (4 bits/char), status kept ≤ 128 chars, retries smaller if Root rejects (old format still decoded; 6 unit tests)
- [x] Fixes: core calls ran on the CPU thread pool (now IO); failed Root calls inside coroutines could crash the app (react/delete etc.; now a toast + log); mention detection differed between highlight / notification / Mentions tab (now one rule: real name, username, display name, replies incl. multi-reply, @everyone); signing out left DMs, mentions, notifications, roles etc. from the old account in memory; taps landed on messages while the chat was still sliding in (the "tapping a channel opened a profile" bug); upload connections never closed; version strings were 0.1.0 / 0.3.0 / 0.3.6 in different places (now all 0.4.0 from one source)
- [ ] Known limit: one lock around the whole core, held during network calls — a slow request delays everything else (needs a bigger refactor)
- [x] Built + installed v0.4.0; starts cleanly. Not yet exercised against the live server (Bloom had been wiped from the phone, needs signing in again)

## Known bugs to check
- [ ] Password field possibly cleared after tapping the eye (seen twice while the user was also using the phone)
- [~] Tapping a channel once opened a message author's profile instead (taps are now blocked while the chat slides in)
- [ ] "Members" count shows only the signed-in user (now shows channel count instead; not installed yet)
