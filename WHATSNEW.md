### Added
- A short welcome on first launch, and the app asks for notifications only when a feature needs them.
- Sign-in lists the Music Assistant servers on your network, on the phone and on Android TV.
- Each server keeps its own account, with Continue as, recently used servers and Forget.
- Change server from Settings while you stay signed in.
- Home shows the Music Assistant rows you choose, in your order.
- Library can show only album artists and filters albums by type, on the phone and on Android TV.
- Android TV and Android Automotive: sign in with Home Assistant.

### Improved
- The home screen widget shows the artist on a one-row widget, with a bigger title. (#73)
- Servers on your network are found more reliably on Android 13 and older.
- The phone speaker resumes after a Bluetooth or server reconnect only when it should.
- Android Automotive: signing in, the dock and the notification open the media center.
- Android TV: the keyboard opens when you press OK.

### Fixed
- Typing a server address with a space no longer closes the app.
- Signing in on the wrong port now says which port Music Assistant uses.
- A password that starts or ends with a space now signs in.
- A failed switch to another server keeps you on the server you were using.
- After switching servers, an artist you blocked on the other server no longer blocks a different artist.
- Switching between servers no longer erases what the app learned about your artists.
- Android TV: BACK in a text field acts once, BACK in Change server returns to your server, and reopening the app after Change server reconnects.
