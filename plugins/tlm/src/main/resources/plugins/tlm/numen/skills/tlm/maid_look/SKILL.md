---
name: maid_look
description: How a Touhou Little Maid model works as your look — where model ids come from, how a maid model covers every other look once worn, and the tlm.skin functions for listing, wearing and removing models.
---

# Maid model look

This machine has Touhou Little Maid installed, so you can wear its models. The functions are in the `tlm.skin` group: `list` to look, `wear` to change, `remove` to take off; look up how to call them with
ApiSearch (select:tlm.skin.wear).

- A model id is whatever `tlm.skin.list` returns (it can search by name, for example "灵梦"); a model not in the list is not installed.
- A maid model **covers the whole body**. While you wear one, no other look (a YSM model, your own skin) shows through at all — those other commands still succeed, but nothing visible changes. To switch to another look, take the maid model off first.
