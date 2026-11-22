Link owners want to remember why they made a link, and to pull click numbers into a spreadsheet.

1. Links get an optional `note` (plain text, at most 280 characters) when they are created. It is returned with the link on create and get.
2. Add `GET /api/v1/links/{code}/stats.csv`: the link's daily clicks as CSV (`day,clicks`, one row per day, zero-filled), with the same `from`/`to` range rules and owner check as the JSON stats.
