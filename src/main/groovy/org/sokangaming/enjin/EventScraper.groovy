package org.sokangaming.enjin

import com.microsoft.playwright.*
import groovy.transform.Canonical
import groovy.transform.Synchronized
import groovy.util.logging.Log
import org.apache.commons.collections4.list.SetUniqueList
import org.mapdb.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import java.util.function.Function
import java.util.regex.Pattern
import java.util.stream.Collectors
import org.apache.commons.lang3.exception.ExceptionUtils

@Log
class EventScraper {
    private Atomic.Var<LocalDateTime> lastRun = null
    private Playwright pw = null
    private BrowserType ff = null
    private Browser browser = null
    private BrowserContext context = null
    private Page page = null
    private Config conf = null
    private BrowserType.LaunchOptions lo = null
    private DB db = null
    private HTreeMap<LocalDate, Calen> events = null
    private ProxyGetter proxy = null
    private String currentProxy = null

    //Constant strings
    private static final String xpLoginLink = "//a[@href='/login' and .='Login']"
    private static final String xpUsername = "//*[@name='username']"
    private static final String xpPassword = "//input[@type='password']"
    private static final String xpLoginButton = "//input[@type='submit' and @value='Login']"
    private static final String xpMonth = "//div[contains(@class, 'calendar-container')]/div[contains(@class, 'block-title')]/div[contains(@class, 'text')]/span[contains(@class, 'mask')]"
    private static final String xpDayTd = "//td[contains(@class, 'fc-day')]"
    private static final String xpDayNumber = "xpath=div[contains(@class, 'fc-day-number')]"
    private static final String csEventBoxesImage = ".fc-event-image > .desc"
    private static final String csEventBoxes = ".fc-event"
    private static final Pattern classRx = Pattern.compile(".*fc-day(\\d+).*")
    private static final DateTimeFormatter mmmmyyyy = DateTimeFormatter.ofPattern("MMMM yyyy")
    public static final DateTimeFormatter hmma = DateTimeFormatter.ofPattern("h:mma")

    EventScraper(Config c) {
        conf = c;

        //MapDB
        db = DBMaker.fileDB("calendar.db").fileMmapEnable().closeOnJvmShutdown().make()
        events = db.hashMap("events").keySerializer(Serializer.JAVA).valueSerializer(Serializer.JAVA).counterEnable().expireAfterCreate(60, TimeUnit.DAYS).createOrOpen() as HTreeMap<LocalDate, Calen>
        lastRun = db.atomicVar("lastRun", Serializer.JAVA).createOrOpen() as Atomic.Var<LocalDateTime>

        //Playwright
        pw = Playwright.create()
        ff = pw.firefox()
        lo = new BrowserType.LaunchOptions().setHeadless(true).setProxy("per-context")
        browser = ff.launch(lo)
        context = browser.newContext()

        proxy = new ProxyGetter(conf)
    }

    Calen getCalendar(LocalDate when) {
        return getCalendar(when, false)
    }

    @Synchronized
    Calen getCalendar(LocalDate when, boolean forceUpdate) {
        if(forceUpdate || lastRun == null || lastRun.get() == null || lastRun.get().isBefore(LocalDateTime.now().minusSeconds(conf.getUpdateFrequency()))) {
            log.info("Need to update.")
            update();
            log.info("Update complete.")
        }
        else {
            log.info("No calendar update needed.")
        }

        return events.getOrDefault(when, null)
    }

    private void newContext() {
        try {
            log.info("Grabbing a new proxy.")
            currentProxy = proxy.getRandomProxy()
            log.info(String.format("Got proxy %s", currentProxy))
        }
        catch(IOException ioe) {

            log.severe(ExceptionUtils.getStackTrace(ioe))
            currentProxy = null
        }
        if(currentProxy == null) {
            log.severe("Couldn't get a new proxy!")
            System.exit(2)
        }

        context = browser.newContext(new Browser.NewContextOptions().setProxy(getProxy()))
    }

    private com.microsoft.playwright.options.Proxy getProxy() {
        var proxy = new com.microsoft.playwright.options.Proxy(String.format("https://%s:%d", currentProxy, conf.getProxyPort()))
        proxy.username = conf.proxyUsername
        proxy.password = conf.proxyPassword
        proxy
    }

    private static Page.WaitForSelectorOptions waitFor(int seconds) {
        return new Page.WaitForSelectorOptions().setTimeout(1000 * seconds);
    }

    private void update() {
        boolean error = false;
        do {
            try {
                //Proxy selection - use existing unless it doesn't test successfully
                if(currentProxy == null || !proxy.testProxy(currentProxy)) {
                    newContext() //creates a new BrowserContext with a different proxy
                }

                //Page setup
                var npo = new Browser.NewPageOptions();
                page = browser.newPage(npo)
                page.setViewportSize(1920, 1080);
                page.setDefaultNavigationTimeout(120 * 1000);

                //Repeatedly try to log in up to 3 times
                page.navigate(conf.getCalendarUrl()).finished()
                boolean loggedIn
                boolean alreadyLogged = false;
                int tries = 0;
                ElementHandle element
                do {
                    element = select(xpLoginLink, 30)
                    if(element == null) {
                        loggedIn = true
                        log.info("Already logged in!")
                        alreadyLogged = true
                        break
                    }
                    else {
                        loggedIn = login()
                    }

                    tries++
                }
                while(!loggedIn && tries <= 3)

                if(!loggedIn) {
                    throw new RuntimeException("Tried to log in 3 times but failed!")
                }

                //We're logged in - head to the calendar
                if(!alreadyLogged) {
                    page.navigate(conf.getCalendarUrl()).finished()
                }

                //Figure out what days are on the calendar
                var dateElement = select(xpMonth)
                if(dateElement != null) {
                    var mainMonth = YearMonth.parse(dateElement.textContent(), mmmmyyyy).atDay(1)
                    var days = new ArrayList<MyDay>()
                    sleep(20 * 1000)
                    var dayElements = page.querySelectorAll(xpDayTd)
                    for(ElementHandle dayElement : dayElements) {
                        var itsClass = dayElement.getAttribute("class")
                        var matcher = classRx.matcher(itsClass)
                        if(matcher.find()) {
                            var dayNum = Integer.parseInt(matcher.group(1))
                            var dayns = dayElement.querySelectorAll(xpDayNumber)
                            var daynstr = dayns.get(0).textContent()
                            var dom = Integer.parseInt(daynstr)
                            days.add(dayNum, new MyDay(element: dayElement, dayOfMonth: dom, month: null, year: null, date: null))
                        }
                        else {
                            throw new RuntimeException("Code-up error: can't find required class in " + itsClass)
                        }

                    }

                    //Fill in month and year for each cell in table
                    var foundCurrentMonthYet = false
                    var currMonthNum = mainMonth.monthValue
                    var prevMonthNum = mainMonth.minusMonths(1).monthValue
                    var nextMonthNum = mainMonth.plusMonths(1).monthValue
                    var currYearNum = mainMonth.getYear();
                    var prevYearNum = mainMonth.minusMonths(1).year
                    var nextYearNum = mainMonth.plusMonths(1).year
                    for(def day : days) {
                        var eltClass = day.element.getAttribute("class")
                        var elementIsCurrentMonth = !eltClass.contains("fc-other-month")
                        if (elementIsCurrentMonth) {
                            //This is exactly the month listed on the calendar
                            foundCurrentMonthYet = true
                            day.month = currMonthNum
                            day.year = currYearNum
                        } else {
                            if (foundCurrentMonthYet) {
                                //This is the month AFTER the month listed on the calendar
                                day.setMonth(nextMonthNum)
                                day.setYear(nextYearNum)
                            } else {
                                //This is the month BEFORE the month listed on the calendar
                                day.setMonth(prevMonthNum)
                                day.setYear(prevYearNum)
                            }
                        }
                        day.date = LocalDate.of(day.year, day.month, day.dayOfMonth)
                    }

                    //Get all the event box elements from the page
                    var eventBoxes = page.querySelectorAll(csEventBoxes)
                    var eventBoxesImage = page.querySelectorAll(csEventBoxesImage)
                    if((eventBoxes == null || eventBoxes.size() == 0) && (eventBoxesImage == null || eventBoxesImage.size() == 0)) {
                        throw new RuntimeException("ERROR: Wasn't able to see ANY events!")
                    }

                    //Remove birthdays because they confuse the parser
                    eventBoxes = eventBoxes.stream().filter(this::filterFunc).collect(Collectors.toList())
                    eventBoxesImage = eventBoxesImage.stream().filter(this::filterFunc).collect(Collectors.toList())

                    //Generic processor that tries to match up days and event box elements
                    //Accepts a parser function for parsing different elements different ways, e.g. text-only vs. image-based event boxes
                    BiConsumer<List<ElementHandle>, Function<ElementHandle, Evt>> processEltsFunc = (List<ElementHandle> arr, Function<ElementHandle, Evt> parseFunc) -> {
                        for(ElementHandle eventBox : arr) {
                            var foundDay = false
                            for(MyDay day : days) {
                                if(inside(day.element, eventBox)) {
                                    //We found the cell we belong in
                                    foundDay = true
                                    var dd = day.date
                                    //Scrape values
                                    var evt = parseFunc.apply(eventBox)
                                    if(evt.time) {
                                        if(events.containsKey(dd)) {
                                            //Put event in existing Calen
                                            if(events.get(dd) == null) {
                                                final var ar = new ArrayList<Evt>()
                                                ar.add(evt)
                                                events.put(dd, new Calen(date: dd, events: SetUniqueList.setUniqueList(ar)))
                                            }
                                            else {
                                                var caln = events.get(dd)
                                                var evts = caln.events()
                                                if (evts == null) {
                                                    evts = SetUniqueList.setUniqueList(new ArrayList<>())
                                                }
                                                evts.add(evt)
                                                events.put(dd, caln)
                                            }
                                        }
                                        else {
                                            //New Calen needed
                                            var ar = new ArrayList<Evt>()
                                            ar.add(evt)
                                            events.put(dd, new Calen(date: dd, events: SetUniqueList.setUniqueList(ar)))
                                        }
                                    }
                                    else {
                                        log.severe("Found an event box without a time! " + evt.toString());
                                    }
                                    break
                                }
                            }
                            if(!foundDay) {
                                var evt = parseFunc.apply(eventBox)
                                log.severe(String.format("Not sure where %s belongs; didn't find a day for it. Rect: %s", evt.toString(), getBoundingClientRect(eventBox).toString()))
                            }
                        }
                    };

                    //Before running the BiConsumer to process the event boxes, clear all Calens to account for changed/nuked events
                    events.entrySet().forEach((calenValue) -> {
                        var evts = calenValue.value.events
                        if(evts != null) {
                            evts.clear()
                            events.put(calenValue.key, calenValue.value)
                        }
                    });

                    //Process all the non-image event boxes
                    processEltsFunc.accept(eventBoxes, (ElementHandle elt) -> {
                        var time = ""
                        try {
                            var times = elt.querySelectorAll("xpath=a/span[contains(@class,'fc-event-time')]")
                            time = reifyTime(times.get(0).textContent())
                        }
                        catch(Exception e) {
                            //Deliberately squelch
                        }
                        return new Evt(name: elt.querySelector(".fc-event-title").innerText(),
                                recurs: time.startsWith("R"),
                                time: LocalTime.parse(time.replace("R", ""), hmma))
                    });

                    //Process all the image event boxes
                    processEltsFunc.accept(eventBoxesImage, (ElementHandle elt) -> {
                        var descWrapperText = elt.querySelector(".desc-wrapper").innerText()
                        var lines = descWrapperText.split("\n")
                        var name = Arrays.copyOfRange(lines, 1, lines.length)
                        return new Evt(name: String.join(" ", name), time: LocalTime.parse(reifyTime(lines[0].replace("R", "")), hmma),
                                recurs: descWrapperText.startsWith("R"))
                    });

                    //Sort each day's events by their time
                    for(LocalDate key : events.keySet()) {
                        var calen = events.get(key)
                        if(calen != null) {
                            var evts = calen.getEvents()
                            if(evts != null) {
                                //SetUniqueList's ListIterator does not support 'set' method (lazy Apache...)
                                var arevts = new ArrayList<Evt>(evts)
                                arevts.sort(Comparator.comparing(Evt::getTime))
                                calen.setEvents(SetUniqueList.setUniqueList(arevts))
                                updateCalen(key, calen)
                            }
                        }
                    }
                }
                else {
                    throw new RuntimeException("ERROR: Wasn't able to get the current month!")
                }

                //Only update lastRun on success
                lastRun.set(LocalDateTime.now())
                error = false
            }
            catch(Exception e) {
                //Set error state, wait 10 minutes, force reselection of proxy, and clear cookies
                error = true
                currentProxy = null
                context.clearCookies()
                log.severe(e)
                sleep(600 * 1000,{System.exit(4)})
            }
            finally {
                //Clean up page every time
                page.close()
                page = null
            }
        }
        while(error);
    }

    private static String reifyTime(String input) {
        var uc = input.toUpperCase().replace(" ", "")
        if(uc.startsWith("0")) {
            uc = uc.substring(1)
        }
        return uc + (uc.endsWith("M") ? "" : "M")
    }

    void updateCalen(LocalDate key, Calen value) {
        events.put(key, value)
    }

    private boolean filterFunc(ElementHandle ebox) {
        final var p = ebox.getAttribute("class")
        final var ih = ebox.innerHTML()
        return !p.toLowerCase().contains("birthday") & !ih.toLowerCase().contains("birthday")
    }

    @Canonical
    private static class MyDay {
        ElementHandle element
        int dayOfMonth
        Integer month
        Integer year
        LocalDate date
    }

    private ElementHandle select(String selector) {
        return select(selector, 60)
    }

    private ElementHandle select(String selector, int seconds) {
        try {
            return page.waitForSelector(selector, waitFor(seconds))
        }
        catch(Exception e) {
            log.severe(e)
            return null
        }
    }

    private boolean login() {
        log.info("Need to login.")
        page.navigate(conf.getLoginUrl()).finished()
        var element = select(xpUsername)
        element.type(conf.getEnjinUsername())
        element = select(xpPassword)
        element.type(conf.getEnjinPassword())
        element = select(xpLoginButton)
        element.click()

        element = select(xpLoginLink, 15)
        if(element == null) {
            log.info("Successfully logged in!")
        }
        else {
            log.info("Failed to login...")
        }

        return element == null
    }

    private static ClientRect getBoundingClientRect(ElementHandle el1) {
        var boundingBox = el1.boundingBox()
        return new ClientRect(left: boundingBox.x, right: boundingBox.x + boundingBox.width, top: boundingBox.y, bottom: boundingBox.y + boundingBox.height, x: boundingBox.x, y: boundingBox.y, width: boundingBox.width, height: boundingBox.height);
    }

    private static boolean rinside(ClientRect rect1, ClientRect rect2) {
        ((rect2.getTop() <= rect1.getTop()) && (rect1.getTop() <= rect2.getBottom())) &&
                ((rect2.getTop() <= rect1.getBottom()) && (rect1.getBottom() <= rect2.getBottom())) &&
                ((rect2.getLeft() <= rect1.getLeft()) && (rect1.getLeft() <= rect2.getRight())) &&
                ((rect2.getLeft() <= rect1.getRight()) && (rect1.getRight() <= rect2.getRight()))
    }

    private static boolean inside(ElementHandle el1, ElementHandle el2) {
        var rect1 = getBoundingClientRect(el1)
        var rect2 = getBoundingClientRect(el2)
        var r1 = rinside(rect1, rect2)
        var r2 = rinside(rect2, rect1)
        r1 || r2
    }
}
