-- OpenMW Android touch HUD bridge.
--
-- The Java overlay (ui.controls.Osc) drives ordinary OpenMW input for movement,
-- combat and most shortcuts. The one thing it cannot reach with a plain key
-- event is switching the inventory screen to a specific page, because Morrowind
-- has no default key for "Character", "Magic" or "Map" -- on desktop those are
-- clicked with the mouse.
--
-- This player script maps a handful of keys that OpenMW leaves unbound onto the
-- same calls the game itself makes for its controller tab bar, so the touch
-- shortcuts behave like the console/mouse equivalents without the overlay ever
-- having to move the mouse pointer around while a menu is open.
--
-- Keys (all unbound in OpenMW's defaults, so nothing else reacts to them):
--   U  - inventory
--   F6 - Character / stats page
--   F7 - Magic page
--   F8 - Map page
--   K  - Journal
--
-- If this content file is disabled or missing the shortcut buttons simply do
-- nothing: those keys stay unbound.

local input = require('openmw.input')
local I = require('openmw.interfaces')

local KEY = input.KEY

local function availableWindows()
    local ok, windows = pcall(I.UI.getWindowsForMode, I.UI.MODE.Interface)
    if not ok or type(windows) ~= 'table' then
        return {}
    end
    return windows
end

--- The same guard the built-in playercontrols.lua uses: during character
--- generation the journal is only allowed once the magic window is.
local function journalAllowed()
    return availableWindows()[I.UI.WINDOW.Magic] ~= nil
end

local function isOpen(mode)
    return I.UI.getMode() == mode
end

--- Opens the inventory screen focused on a single page, or closes the
--- interface if that page is already the visible one.
local function showInterfacePage(window)
    if not window then
        return
    end
    local windows = availableWindows()
    if not windows[window] then
        return
    end

    local page = { window }
    if isOpen(I.UI.MODE.Interface) and I.UI.isWindowVisible(window) then
        I.UI.removeMode(I.UI.MODE.Interface)
        return
    end

    I.UI.setMode(I.UI.MODE.Interface, { windows = page })
end

local function toggleJournal()
    if isOpen(I.UI.MODE.Journal) then
        I.UI.removeMode(I.UI.MODE.Journal)
    elseif journalAllowed() then
        I.UI.addMode(I.UI.MODE.Journal)
    end
end

local handlers = {
    [KEY.U] = function() showInterfacePage(I.UI.WINDOW.Inventory) end,
    [KEY.K] = toggleJournal,
    [KEY.F6] = function() showInterfacePage(I.UI.WINDOW.Stats) end,
    [KEY.F7] = function() showInterfacePage(I.UI.WINDOW.Magic) end,
    [KEY.F8] = function() showInterfacePage(I.UI.WINDOW.Map) end,
}

local function onKeyPress(event)
    local handler = handlers[event.code]
    if handler then
        handler()
    end
end

return {
    engineHandlers = {
        onKeyPress = onKeyPress,
    },
}
