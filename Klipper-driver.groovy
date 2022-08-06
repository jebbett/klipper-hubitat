
// Created by jebbett/n3rding 2021
// Uses the Moonraker API (Used with Klipper / Fluidd)
// https://moonraker.readthedocs.io/en/latest/web_api/
// Assumed possible status' "Operational", "Printing", "Pausing", "Paused", "Cancelling", "Error", "Offline", "Disconnected","HostOffline","Complete"
//
// V0.1    2021-12-18    Initial Code
// V0.2    2021-12-18    Fixed offline status check
// V0.3    2021-12-18    Added "Complete" status and updated above with expected status' for reference
// V0.4    2022-03-18    Various fixes
// V0.5    2022-04-24    Changed RunIn to Schedule, changed checks from seconds to minutes in settings
// V0.6    2022-07-09    Fixed refresh rate, fixed multiple updates when single update requested and changed temps to Integers
// V0.7    2022-08-06    Fixed "Complete" status reporting and added option to enable detailed reporting (previous was creating too much noise)
// 
//
metadata {
    definition (name: "Klipper", namespace: "klipper-hubitat", author: "jebbett") {
        capability "Actuator"
		capability "Switch"
        attribute "status", "string"
        // attributes for temperature
		attribute "bed-actual", "number"
		attribute "bed-target", "number"
		// primary extruder
		attribute "tool0-actual", "number"
		attribute "tool0-target", "number"
        //fan
        attribute "fan-percent", "number"
        //print details
        attribute "print-percent", "number"
        attribute "print-time", "date"
        attribute "total-time", "date"
        attribute "slicer-est-time", "date"
        attribute "hubitat-est-time", "date"
        attribute "filament-used-mm", "number"
        attribute "filename", "string"
        attribute "message", "string"
        
        command "GetStatus"
        command "Home"
        command "Cancel"
        command "Pause"
        command "Resume"
        command "gcode", ["string"]
        command "PrintFileName", ["string"]
        command "FIRMWARE_RESTART"
        command "EMERGENCY_STOP"
        command "HOST_RESTART"
        command "SERVER_RESTART"
        command "OS_SHUTDOWN"
        command "OS_REBOOT"
    }

    preferences {
        section("Device Settings:") {
            input "ip", "string", title:"IP Address", description: "", required: true, displayDuringSetup: true
            input "port", "string", title:"Port", description: "", required: true, displayDuringSetup: true, defaultValue: "80"
            input "longCheck", "number", title:"How often to check if printer is printing (minutes)", description: "Set to 0 for none", required: true, displayDuringSetup: true, defaultValue: "10", range:"0..59"
            input "shortCheck", "number", title:"How often to update while printing (minutes)", description: "Set to 0 for none", required: true, displayDuringSetup: true, defaultValue: "1", range:"0..59"
            input "reportDet", "bool", title:"Detailed Reporting", description: "Adding these stats can cause higher load on the platform", defaultValue: false 
            input name: "onWhilePrinting", type: "bool", title: "Shows device as 'On' only while printing", defaultValue: false
            input name: "logging", type: "bool", title: "Enable debug logging", defaultValue: false 
        }
    }
}        

void initialize(){
    updated()
}

def updated(){
	sendEvent(name: "status", value: "Waiting Update" )
    unschedule()
    if (logging) {
		log.warn "Debug logging enabled for 30 mins..."
		runIn(1800,logsDisabled)
    }
    GetStatus()
}

def logsDisabled(){
    log.warn "Debug logging disabled..."
    device.updateSetting("logging",[value:"false",type:"bool"])
}

def logDebug(string){
    if (logging) log.debug string
}

def printerOffline(status){
    if (longCheck != 0) schedule('0 */' + longCheck + ' * ? * *', GetStatus) //runIn(longCheck, GetStatus)
    if (device.currentValue("status") != status){
        sendEvent(name: "switch", value: "off", isStateChange: true)
        sendEvent(name: "status", value: status)
        sendEvent(name: "bed-target", value: 0)
        sendEvent(name: "tool0-target", value: 0)
        sendEvent(name: "print-percent", value: 0)
        sendEvent(name: "filename", value: "NA")
        sendEvent(name: "message", value: "NA")
        sendEvent(name: "fan-percent", value: 0)
        
        if (reportDet){
            sendEvent(name: "bed-actual", value: 0)
            sendEvent(name: "tool0-actual", value: 0)
            sendEvent(name: "print-time", value: new GregorianCalendar( 0, 0, 0, 0, 0, 0, 0 ).time.format('HH:mm:ss'))
            sendEvent(name: "total-time", value: new GregorianCalendar( 0, 0, 0, 0, 0, 0, 0 ).time.format('HH:mm:ss'))
            sendEvent(name: "filament-used-mm", value: 0)
            sendEvent(name: "slicer-est-time", value: new GregorianCalendar( 0, 0, 0, 0, 0, 0, 0 ).time.format('HH:mm:ss'))
            sendEvent(name: "hubitat-est-time", value: new GregorianCalendar( 0, 0, 0, 0, 0, 0, 0 ).time.format('HH:mm:ss'))
        }
    }
}

def GetStatus(){
    
    printing = ["Printing", "Pausing", "Paused", "Cancelling"]
    resp = queryPrinter("/api/printer")
    
    if(!resp){
        logDebug("Unable to communicate with Moonraker/Klipper")
        printerOffline("HostOffline")
    }else if(resp.state.flags.closedOrError){
        logDebug("Printer showing as Errored or Offline")
        printerOffline(resp.state.text)
    }else{
        pstatus = resp.state.text
        if (device.currentValue("status") == "Printing" && pstatus == "Operational") {
            pstatus = "Complete"
        }else{
            pstatus = pstat.print_stats.state
        }
        sendEvent(name: "status", value: pstatus)
        // Set switch to On if in a printing state or if "On" while printing is false
        if(!onWhilePrinting || resp.state.text in printing){
            if (device.currentValue("switch") != "on") sendEvent(name: "switch", value: "on", isStateChange: true) 
        }else{
            sendEvent(name: "switch", value: "off", isStateChange: true)
        }

        sendEvent(name: "bed-target", value: resp.temperature.bed.target)
        sendEvent(name: "tool0-target", value: resp.temperature.tool0.target)
        
        if (reportDet){
            sendEvent(name: "bed-actual", value: resp.temperature.bed.actual.toInteger())
            sendEvent(name: "tool0-actual", value: resp.temperature.tool0.actual.toInteger())
        }
        
        
        print = queryPrinter("/printer/objects/query","fan&print_stats&display_status")
        pstat = print.result.status
        fn = pstat.print_stats.filename.replace(" ", "%20")
        printTime = pstat.print_stats.print_duration.toInteger()
        printPercent = (pstat.display_status.progress * 100).toDouble()
        if (fn) {
            gcode = queryPrinter("/server/files/metadata","filename=${fn}")
            estTime = gcode.result.estimated_time.toInteger()
            if (printTime == 0 && reportDet){
                remTime == estTime
            }else{
                remTime = (((printTime / printPercent)*100) - printTime).toInteger()
            }
            if (!remTime){remTime = 0} //set to zero if no time yet
        }else{
            estTime = 0
            remTime = 0
        }
        
        sendEvent(name: "fan-percent", value: pstat.fan.speed.toInteger() * 100)
        sendEvent(name: "print-percent", value: printPercent.toInteger())
        sendEvent(name: "filename", value: pstat.print_stats.filename)
        sendEvent(name: "message", value: pstat.print_stats.message)
        
        if (reportDet){  
            sendEvent(name: "print-time", value: new GregorianCalendar( 0, 0, 0, 0, 0, printTime, 0 ).time.format('HH:mm:ss'))
            sendEvent(name: "total-time", value: new GregorianCalendar( 0, 0, 0, 0, 0, pstat.print_stats.total_duration.toInteger(), 0 ).time.format('HH:mm:ss'))
            sendEvent(name: "filament-used-mm", value: pstat.print_stats.filament_used.toInteger())
            sendEvent(name: "slicer-est-time", value: new GregorianCalendar( 0, 0, 0, 0, 0, estTime - printTime, 0 ).time.format('HH:mm:ss'))
            sendEvent(name: "hubitat-est-time", value: new GregorianCalendar( 0, 0, 0, 0, 0, remTime, 0 ).time.format('HH:mm:ss'))
        }
        //Schedule timer
        if(resp.state.text in printing && shortCheck != 0){
            //If printing, paused, cancelling or pausing state (actively doing something) set short timer
            schedule('0 */' + shortCheck + ' * ? * *', GetStatus)
        }else if (longCheck != 0) {
            schedule('0 */' + longCheck + ' * ? * *', GetStatus)
        }
    }
}

def queryPrinter(String path, String query = null) {

    def params = [:]
    params.put("uri", "http://${ip}:${port}")
    params.put("path", path)
    if (query) { params.put("queryString", query) }
    try {
        httpGet(params) { resp ->
            if (resp.data){
               return resp.data
            }
        }
    } catch (Exception e) {
        log.warn "Call failed: ${e.message}"
        return null
    }
}

def sendCommand(String path, String query = null) {
    try{
        if(query){path = path + "?" + query}
        def params = [uri : "http://${ip}:${port}${path}"]
        asynchttpPost('postCallBack', params)
    }
    catch(Exception e){
        if(e.message.toString() != "OK"){log.error e.message}
    }
}

def postCallBack(response, data){
    logDebug("response.status = ${response.status}")
    if(!response.hasError()) logDebug("response.getData() = ${response.getData()}")    
}

def on(){

}
def off(){

}

def Cancel(){
    sendCommand("/printer/print/cancel") 
}
def Resume(){
    sendCommand("/printer/print/resume") 
}
def Pause(){
    sendCommand("/printer/print/pause") 
}

def PrintFileName(filename){    
    sendCommand("/printer/print/start","filename=${filename}") 
}

def OS_SHUTDOWN(){
    sendCommand("/machine/shutdown")
    pauseExecution(1000)
    GetStatus() 
}


def OS_REBOOT(){
    sendCommand("/machine/reboot")
    runIn(60, GetStatus)
}

//GCODES

def gcode(gcode){
    sendCommand("/printer/gcode/script?script=${gcode}")
}

def FIRMWARE_RESTART(){
    sendCommand("/printer/firmware_restart")   
    runIn(10, GetStatus)
}

def EMERGENCY_STOP(){
    sendCommand("/printer/emergency_stop")
    pauseExecution(1000)
    GetStatus() 
}

def HOST_RESTART(){
    sendCommand("/printer/restart")
    runIn(10, GetStatus)
}

def SERVER_RESTART(){
    sendCommand("/server/restart")
}

def Home(){
    gcode("G28") 
}
