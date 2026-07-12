/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * For more information, please refer to <https://unlicense.org>
 */

definition(
    name: "Traeger Garage Fan Light Controller",
    namespace: "jlslate",
    author: "jlslate (slate)",
    description: "Links a Traeger grill's power state to a garage door, fan, and optional light",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Traeger Automation", install: true, uninstall: true) {
        section("Devices") {
            input name: "traegerSwitch", type: "capability.switch", title: "Traeger Grill (must expose a grillState attribute)", required: true, multiple: false
            input name: "garageDoor", type: "capability.garageDoorControl", title: "Garage Door", required: true, multiple: false
            input name: "fanSwitch", type: "capability.switch", title: "Fan", required: true, multiple: false
            input name: "lightSwitch", type: "capability.switch", title: "Light (optional)", required: false, multiple: false
        }
        section("Options") {
            input name: "enableLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

@groovy.transform.Field static final List GRILL_ON_STATES = ["idle", "igniting", "preheating", "manual_cook", "custom_cook"]
@groovy.transform.Field static final List GRILL_OFF_STATES = ["shutdown", "sleeping", "offline"]

def initialize() {
    subscribe(traegerSwitch, "grillState", grillStateHandler)
    subscribe(garageDoor, "door", doorHandler)
}

def grillStateHandler(evt) {
    logDebug "Traeger grillState changed to ${evt.value}"
    if (evt.value in GRILL_ON_STATES) {
        unschedule("runOffActions")
        if (garageDoor.currentValue("door") != "open") garageDoor.open()
        if (fanSwitch.currentValue("switch") != "on") fanSwitch.on()
        if (lightSwitch && lightSwitch.currentValue("switch") != "on") lightSwitch.on()
    } else if (evt.value in GRILL_OFF_STATES) {
        // Debounced: the Traeger driver can replay a stale/retained grillState
        // (e.g. shutdown/sleeping) right after a genuine on-state on MQTT
        // reconnect. Wait 5s and re-check before acting, so a transient blip
        // that gets superseded by a real "on" event doesn't slam the door shut.
        runIn(5, "runOffActions")
    }
    // "cool_down" is intentionally ignored: the grill is still venting, so the
    // garage/fan/light stay on until it settles into a genuine off state.
}

def runOffActions() {
    def current = traegerSwitch.currentValue("grillState")
    if (!(current in GRILL_OFF_STATES)) {
        logDebug "runOffActions: grillState is now ${current}, skipping stale off-trigger"
        return
    }
    if (garageDoor.currentValue("door") != "closed") garageDoor.close()
    if (fanSwitch.currentValue("switch") != "off") fanSwitch.off()
    if (lightSwitch && lightSwitch.currentValue("switch") != "off") lightSwitch.off()
}

def doorHandler(evt) {
    logDebug "Garage door changed to ${evt.value}"
    if (evt.value == "closed") {
        // Direct command, unlike grillStateHandler's off path: the door is
        // already closed, so there's no reason to wait for cool_down to finish.
        if (traegerSwitch.currentValue("switch") != "off") traegerSwitch.off()
        if (fanSwitch.currentValue("switch") != "off") fanSwitch.off()
        if (lightSwitch && lightSwitch.currentValue("switch") != "off") lightSwitch.off()
    }
}

def logDebug(msg) {
    if (enableLogging) log.debug msg
}
