This seems to work without issue.

## Entity UUIDs

By default, TMCRS regenerates entity UUIDs.

-keep-entity-uuids if you want to preserve them.  Only do this
if you are importing regions from another world and are sure that
those entities won't have doppelgängers.

I used to think that entities appearing black had something to do with
UUIDs, but after further testing (and realizing that my UUIDs actually
had no effect because I was doing them wrong), it seems unrelated.
I've been able to clear them up by moving far away and then coming back.

## To-do

Generate new entity UUIDs correctly.
According to the wiki, UUID is ignored if UUIDMost and UUIDLeast are already present.
Need to either remove those, or, better yet, just overwrite them.

Consistently work at chunk granularity:

- -error-on-conflict shouldn't trigger unless there is overlapping chunk
  data within overlapping regions.
- -keep should still write into existing region files where chunks are missing.

- Allow -bounds to be specified in chunks instead of regions.
