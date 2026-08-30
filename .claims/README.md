# Device claims

One file per machine. Each says what that machine is working on right now.

**Read before you start anything:**

```powershell
powershell -File scripts\claim.ps1 -Show
```

**Claim a lane when you start:**

```powershell
powershell -File scripts\claim.ps1 -Lane mobile -Task "Android Task 2 — Answer model"
```

**Release when you stop:**

```powershell
powershell -File scripts\claim.ps1 -Release
```

## Why one file per device

A single shared `WHO_IS_DOING_WHAT.md` would conflict every time two machines
updated it at the same moment — exactly the situation it exists to prevent.
Separate files never touch each other, so updating a claim can never be the
thing that breaks your push.

The script commits only your own claim file, rebases, then pushes. It cannot
carry unrelated work with it and it cannot clobber anyone.

## Lanes

`mobile` · `retrieval` · `dashboard` · `eval` · `docs`

Two machines in different lanes never conflict. Two in the same lane are fine
if they are in different files. Two in the same *file* is the thing to avoid.

`docs` is the lane worth respecting most — `README.md` and `CHECKPOINT.md` are
what everyone wants to edit and where changes collide hardest.

## If a claim is stale

Claims are advisory, not locks. Nothing enforces them. If a claim is hours old
and that machine is clearly not active, ask before assuming — then take the
lane and update your own file.
