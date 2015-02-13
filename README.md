This seems to work without issue.

## Entity UUIDs

By default, TMCRS regenerates entity UUIDs.

Not doing so seems to result in entities colored very darkly
(appearing black unless you look real close).
I'm not sure why this is, and I'm not 100% convinced that it's
caused by keeping UUIDs, but there was a correlation there during
my (very limited) testing.

-keep-entity-uuids if you want to preserve them.  Only do this
if you are importing regions from another world and are sure that
those entities won't have doppelgängers.
