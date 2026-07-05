## dBans Telegram addon

This addon extends **dBans** by delivering punishment notifications directly to selected administrators via a Telegram
bot. Whenever a configured punishment event occurs, the addon sends a detailed private message to the configured
Telegram users, allowing staff members to stay informed without actively monitoring the server.

The addon is built against **dBans API 2.0.0**, which is the latest stable API version as of **5 July 2026**. Future
releases of the addon are intended to closely follow API updates to maintain compatibility. If a newer API version is
released before the addon is updated, you are welcome to open an issue or submit a pull request with the necessary
compatibility changes.

The addon includes built in support for **English**, **Russian**, and **German**. The preferred language can be selected
in the addon's `config.yml`. You can also configure the server's time zone to ensure that all timestamps included in
Telegram notifications accurately reflect your local time.

The configuration is designed to be flexible and allows you to choose exactly which events should trigger notifications.
Notifications can be enabled or disabled independently for the following punishment lifecycle events:

* Punishment creation
* Punishment revocation
* Punishment modification
* Punishment expiration

For punishment creation events, you can additionally specify which punishment types should be ignored by the addon. The
supported types are:

* `BAN`
* `MUTE`
* `KICK`
* `IP_BAN`
* `JAIL`
* `WARNING`

The goal of this addon is to provide a simple and reliable way to integrate **dBans** with **Telegram**, making server
moderation more convenient by ensuring that important punishment events are delivered directly to your administrators,
wherever they are.

I hope it helps you meet your requirements and makes using **dBans** alongside the Telegram messenger more convenient. 
