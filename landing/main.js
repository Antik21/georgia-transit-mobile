// Fill in store and bot links here once they exist; null renders a visible "link coming" placeholder.
const LINKS = {
  web: "https://antik21-georgia-transit-web.onrender.com/",
  telegram: null,
  android: null,
  ios: null,
};

const ru = {
  pageTitle: "Geo Transit — автобусы Батуми в реальном времени",
  skip: "Перейти к содержимому",
  navLabel: "Навигация",
  navFeatures: "Возможности",
  navPlanner: "Из А в Б",
  navPlatforms: "Платформы",
  langLabel: "Язык",
  themeLabel: "Сменить тему",
  heroCity: "Батуми, Грузия",
  heroTitle: "Автобусы Батуми на карте — прямо сейчас",
  heroLead: "Geo Transit показывает, где едут автобусы, примерно когда они будут на вашей остановке и как добраться из точки А в точку Б.",
  ctaWeb: "Открыть веб-версию",
  ctaPlatforms: "Android, iOS и Telegram",
  factFree: "Без регистрации",
  factLangs: "Русский, English, ქართული",
  factTheme: "Светлая и тёмная тема",
  featuresTitle: "Всё, что нужно на остановке",
  featuresLead: "Четыре вещи, ради которых открывают Geo Transit. Каждая — на настоящих данных Батуми.",
  f1Title: "Автобусы в движении",
  f1Body: "Все автобусы города на одной карте. Видно, где сейчас едет нужный номер и сколько машин на линии.",
  f2Title: "Когда придёт автобус",
  f2Body: "Откройте остановку рядом — увидите номера маршрутов и сколько минут до прибытия.",
  f2Note: "Время — оценка по движению автобусов, а не официальное расписание.",
  f3Title: "Только ваши маршруты",
  f3Body: "Отметьте нужные номера — их линии и автобусы на карте окрасятся в цвета маршрутов, остальное отойдёт на второй план.",
  plannerTitle: "Из точки А в точку Б",
  plannerLead: "Удерживайте палец на карте, чтобы отметить начало и конец пути. Geo Transit предложит варианты на автобусах и подпишет, чем каждый хорош.",
  chipsLabel: "Подписи вариантов",
  labelOptimal: "Оптимальный",
  labelFastest: "Быстрее всего",
  labelLeastWalking: "Меньше пешком",
  labelNoTransfers: "Без пересадок",
  legWalkTitle: "Пешие участки",
  legWalkBody: "Сколько метров и минут идти до остановки, между остановками и до места.",
  legBusTitle: "Ближайшие автобусы",
  legBusBody: "Ожидание и время в пути с учётом автобусов, которые уже едут к остановке.",
  legPrivacyTitle: "Точки не сохраняются",
  legPrivacyBody: "Начало и конец пути нужны только для расчёта и нигде не хранятся.",
  plannerNote: "Сейчас в веб-версии и Telegram; в приложениях для Android и iOS — в работе. Всё время примерное и не является официальным расписанием.",
  platformsTitle: "Там, где вам удобно",
  platformsLead: "Geo Transit работает на Android, iOS, в браузере и внутри Telegram. Логика общая, поэтому карта и время прибытия везде одинаковые.",
  pAndroid: "Приложение для телефонов на Android.",
  pIos: "Приложение для iPhone.",
  webTitle: "Веб",
  pWeb: "В любом браузере на телефоне или компьютере, без установки.",
  pTelegram: "Мини-приложение прямо в Telegram — ничего не нужно устанавливать.",
  linkSoon: "Ссылка появится здесь",
  linkWeb: "Открыть сайт",
  linkAndroid: "Открыть в Google Play",
  linkIos: "Открыть в App Store",
  linkTelegram: "Открыть в Telegram",
  footerCity: "Начинаем с Батуми.",
  footerNote: "Время прибытия и длительность поездки — примерные оценки, не официальное расписание. Картографические данные на снимках экрана © участники OpenStreetMap.",
  altLiveMap: "Карта Батуми с автобусами в реальном времени и списком ближайших остановок",
  altLiveMapDark: "Карта Батуми с автобусами в реальном времени в тёмной теме",
  altPlanner: "Путь из точки А в точку Б: вариант «Оптимальный» и «Быстрее всего» на автобусе 7A, около 38 минут",
  altLiveMapNearby: "Автобусы Батуми на карте вокруг текущего местоположения",
  altArrivals: "Остановка Akaki Tsereteli Street №2: маршруты 8, 1A и 20 через 1 минуту",
  altRoutesList: "Список маршрутов Батуми с цветными номерами, выбраны 1A, 8 и 20",
  altRoutesMap: "Маршруты 1, 4 и 6 на карте своими цветами",
  altPlannerOptions: "Варианты пути: 38 минут на автобусе 7A и 53 минуты на автобусе 11, с пешими участками",
};

const en = {
  pageTitle: "Geo Transit — Batumi buses in real time",
  skip: "Skip to content",
  navLabel: "Navigation",
  navFeatures: "Features",
  navPlanner: "A to B",
  navPlatforms: "Platforms",
  langLabel: "Language",
  themeLabel: "Switch theme",
  heroCity: "Batumi, Georgia",
  heroTitle: "Batumi buses on the map, right now",
  heroLead: "Geo Transit shows where the buses are, roughly when they reach your stop, and how to get from A to B.",
  ctaWeb: "Open the web app",
  ctaPlatforms: "Android, iOS and Telegram",
  factFree: "No sign-up",
  factLangs: "Русский, English, ქართული",
  factTheme: "Light and dark theme",
  featuresTitle: "Everything you need at the stop",
  featuresLead: "Four things people open Geo Transit for. Each one runs on live Batumi data.",
  f1Title: "Buses on the move",
  f1Body: "Every bus in the city on one map. See where your number is right now and how many buses are on the line.",
  f2Title: "When the bus arrives",
  f2Body: "Open a nearby stop to see which routes are coming and how many minutes away they are.",
  f2Note: "Times are estimates from bus movement, not an official timetable.",
  f3Title: "Just your routes",
  f3Body: "Pick the numbers you ride. Their lines and buses take on the route colours; everything else steps back.",
  plannerTitle: "From A to B",
  plannerLead: "Press and hold on the map to set where you start and where you're going. Geo Transit suggests bus options and labels what each one is good at.",
  chipsLabel: "Option labels",
  labelOptimal: "Best",
  labelFastest: "Fastest",
  labelLeastWalking: "Least walking",
  labelNoTransfers: "No transfers",
  legWalkTitle: "Walking parts",
  legWalkBody: "How many metres and minutes to the stop, between stops and to your destination.",
  legBusTitle: "Nearest buses",
  legBusBody: "Wait and ride times account for buses already heading to the stop.",
  legPrivacyTitle: "Your points aren't stored",
  legPrivacyBody: "Start and end points are used only to plan the trip and are never kept.",
  plannerNote: "Available now on the web and in Telegram; coming to the Android and iOS apps. All times are approximate, not an official timetable.",
  platformsTitle: "Wherever suits you",
  platformsLead: "Geo Transit runs on Android, iOS, in the browser and inside Telegram. They share one core, so the map and arrival times match everywhere.",
  pAndroid: "The app for Android phones.",
  pIos: "The app for iPhone.",
  webTitle: "Web",
  pWeb: "In any browser on your phone or computer, nothing to install.",
  pTelegram: "A Mini App right inside Telegram, nothing to install.",
  linkSoon: "Link will appear here",
  linkWeb: "Open the site",
  linkAndroid: "Get it on Google Play",
  linkIos: "Get it on the App Store",
  linkTelegram: "Open in Telegram",
  footerCity: "Starting with Batumi.",
  footerNote: "Arrival and trip times are approximate estimates, not an official timetable. Map data in screenshots © OpenStreetMap contributors.",
  altLiveMap: "Map of Batumi with live buses and a list of nearby stops",
  altLiveMapDark: "Map of Batumi with live buses in the dark theme",
  altPlanner: "Trip from A to B: the Best and Fastest option on bus 7A, about 38 minutes",
  altLiveMapNearby: "Batumi buses on the map around the current location",
  altArrivals: "Akaki Tsereteli Street No. 2 stop: routes 8, 1A and 20 arriving in 1 minute",
  altRoutesList: "Batumi route list with coloured numbers, 1A, 8 and 20 selected",
  altRoutesMap: "Routes 1, 4 and 6 drawn on the map in their colours",
  altPlannerOptions: "Trip options: 38 minutes on bus 7A and 53 minutes on bus 11, with walking parts",
};

const ka = {
  pageTitle: "Geo Transit — ბათუმის ავტობუსები რეალურ დროში",
  skip: "შინაარსზე გადასვლა",
  navLabel: "ნავიგაცია",
  navFeatures: "შესაძლებლობები",
  navPlanner: "A-დან B-მდე",
  navPlatforms: "პლატფორმები",
  langLabel: "ენა",
  themeLabel: "თემის შეცვლა",
  heroCity: "ბათუმი, საქართველო",
  heroTitle: "ბათუმის ავტობუსები რუკაზე, ახლავე",
  heroLead: "Geo Transit გაჩვენებთ, სად მოძრაობენ ავტობუსები, დაახლოებით როდის მოვლენ თქვენს გაჩერებაზე და როგორ მიხვიდეთ A წერტილიდან B წერტილამდე.",
  ctaWeb: "ვებ-ვერსიის გახსნა",
  ctaPlatforms: "Android, iOS და Telegram",
  factFree: "რეგისტრაციის გარეშე",
  factLangs: "Русский, English, ქართული",
  factTheme: "ღია და მუქი თემა",
  featuresTitle: "ყველაფერი, რაც გაჩერებაზე გჭირდებათ",
  featuresLead: "ოთხი რამ, რისთვისაც Geo Transit-ს ხსნიან. თითოეული ბათუმის ნამდვილ მონაცემებზე მუშაობს.",
  f1Title: "ავტობუსები მოძრაობაში",
  f1Body: "ქალაქის ყველა ავტობუსი ერთ რუკაზე. ხედავთ, სად არის ახლა საჭირო ნომერი და რამდენი ავტობუსია ხაზზე.",
  f2Title: "როდის მოვა ავტობუსი",
  f2Body: "გახსენით ახლომდებარე გაჩერება და ნახავთ მარშრუტების ნომრებს და მოსვლამდე დარჩენილ წუთებს.",
  f2Note: "დრო ავტობუსების მოძრაობაზე დაფუძნებული შეფასებაა და არა ოფიციალური განრიგი.",
  f3Title: "მხოლოდ თქვენი მარშრუტები",
  f3Body: "მონიშნეთ საჭირო ნომრები — მათი ხაზები და ავტობუსები რუკაზე მარშრუტის ფერებით გამოჩნდება.",
  plannerTitle: "A წერტილიდან B წერტილამდე",
  plannerLead: "ხანგრძლივად დააჭირეთ რუკას, რომ მონიშნოთ გზის დასაწყისი და დასასრული. Geo Transit შემოგთავაზებთ ავტობუსით მგზავრობის ვარიანტებს და მიუთითებს, რით არის თითოეული კარგი.",
  chipsLabel: "ვარიანტების ნიშნები",
  labelOptimal: "ოპტიმალური",
  labelFastest: "ყველაზე სწრაფი",
  labelLeastWalking: "ყველაზე ნაკლები სიარული",
  labelNoTransfers: "გადაჯდომის გარეშე",
  legWalkTitle: "ფეხით მონაკვეთები",
  legWalkBody: "რამდენი მეტრი და წუთი ფეხით გაჩერებამდე, გაჩერებებს შორის და დანიშნულებამდე.",
  legBusTitle: "უახლოესი ავტობუსები",
  legBusBody: "ლოდინისა და მგზავრობის დრო ითვალისწინებს ავტობუსებს, რომლებიც უკვე მიემართებიან გაჩერებისკენ.",
  legPrivacyTitle: "წერტილები არ ინახება",
  legPrivacyBody: "გზის დასაწყისი და დასასრული მხოლოდ გამოთვლისთვის გამოიყენება და არსად ინახება.",
  plannerNote: "ახლა ხელმისაწვდომია ვებ-ვერსიასა და Telegram-ში; Android-ისა და iOS-ის აპებისთვის მზადდება. ყველა დრო მიახლოებითია და არ არის ოფიციალური განრიგი.",
  platformsTitle: "იქ, სადაც თქვენთვის მოსახერხებელია",
  platformsLead: "Geo Transit მუშაობს Android-ზე, iOS-ზე, ბრაუზერში და Telegram-ში. ლოგიკა საერთოა, ამიტომ რუკა და მოსვლის დრო ყველგან ერთნაირია.",
  pAndroid: "აპი Android ტელეფონებისთვის.",
  pIos: "აპი iPhone-ისთვის.",
  webTitle: "ვები",
  pWeb: "ნებისმიერ ბრაუზერში, ტელეფონზე ან კომპიუტერზე, ინსტალაციის გარეშე.",
  pTelegram: "მინი-აპი პირდაპირ Telegram-ში, ინსტალაციის გარეშე.",
  linkSoon: "ბმული აქ გამოჩნდება",
  linkWeb: "საიტის გახსნა",
  linkAndroid: "Google Play-ში გახსნა",
  linkIos: "App Store-ში გახსნა",
  linkTelegram: "Telegram-ში გახსნა",
  footerCity: "ვიწყებთ ბათუმით.",
  footerNote: "მოსვლისა და მგზავრობის დრო მიახლოებითი შეფასებაა და არა ოფიციალური განრიგი. რუკის მონაცემები ეკრანის სურათებზე © OpenStreetMap-ის მონაწილეები.",
  altLiveMap: "ბათუმის რუკა რეალურ დროში მოძრავი ავტობუსებით და ახლომდებარე გაჩერებების სიით",
  altLiveMapDark: "ბათუმის რუკა რეალურ დროში მოძრავი ავტობუსებით, მუქი თემა",
  altPlanner: "გზა A-დან B-მდე: ვარიანტი „ოპტიმალური“ და „ყველაზე სწრაფი“ ავტობუსით 7A, დაახლოებით 38 წუთი",
  altLiveMapNearby: "ბათუმის ავტობუსები რუკაზე მიმდინარე მდებარეობის გარშემო",
  altArrivals: "გაჩერება Akaki Tsereteli Street №2: მარშრუტები 8, 1A და 20 მოვა 1 წუთში",
  altRoutesList: "ბათუმის მარშრუტების სია ფერადი ნომრებით, არჩეულია 1A, 8 და 20",
  altRoutesMap: "მარშრუტები 1, 4 და 6 რუკაზე თავიანთი ფერებით",
  altPlannerOptions: "გზის ვარიანტები: 38 წუთი ავტობუსით 7A და 53 წუთი ავტობუსით 11, ფეხით მონაკვეთებით",
};

const translations = { ru, en, ka };
const linkLabels = { web: "linkWeb", android: "linkAndroid", ios: "linkIos", telegram: "linkTelegram" };
const storage = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch {} },
};

function pickLanguage() {
  const fromUrl = new URLSearchParams(location.search).get("lang");
  if (fromUrl && translations[fromUrl]) return fromUrl;
  const stored = storage.get("gt-landing-lang");
  if (stored && translations[stored]) return stored;
  const browser = (navigator.language || "ru").toLowerCase().split("-")[0];
  return translations[browser] ? browser : "ru";
}

function applyLinks(copy) {
  for (const element of document.querySelectorAll("[data-link]")) {
    const key = element.dataset.link;
    const url = LINKS[key];
    if (!url || element.tagName === "A" || !element.classList.contains("platform__link")) continue;
    const anchor = document.createElement("a");
    anchor.className = "platform__link";
    anchor.href = url;
    anchor.dataset.link = key;
    anchor.dataset.i18n = linkLabels[key];
    anchor.textContent = copy[linkLabels[key]];
    element.replaceWith(anchor);
  }
}

function applyLanguage(language) {
  const copy = translations[language];
  document.documentElement.lang = language;
  document.title = copy.pageTitle;
  for (const element of document.querySelectorAll("[data-i18n]")) element.textContent = copy[element.dataset.i18n];
  for (const element of document.querySelectorAll("[data-i18n-alt]")) element.alt = copy[element.dataset.i18nAlt];
  for (const element of document.querySelectorAll("[data-i18n-aria]")) element.setAttribute("aria-label", copy[element.dataset.i18nAria]);
  for (const button of document.querySelectorAll("[data-lang]")) button.setAttribute("aria-pressed", String(button.dataset.lang === language));
  applyLinks(copy);
}

function currentTheme() {
  const explicit = document.documentElement.dataset.theme;
  if (explicit) return explicit;
  return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

for (const button of document.querySelectorAll("[data-lang]")) {
  button.addEventListener("click", () => {
    storage.set("gt-landing-lang", button.dataset.lang);
    applyLanguage(button.dataset.lang);
  });
}

document.querySelector(".theme-toggle").addEventListener("click", () => {
  const next = currentTheme() === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  storage.set("gt-landing-theme", next);
});

const heroLine = document.querySelector(".hero__line");
for (const stop of document.querySelectorAll(".hero__stop[data-at]")) {
  const point = heroLine.getPointAtLength(heroLine.getTotalLength() * Number(stop.dataset.at));
  stop.setAttribute("cx", point.x.toFixed(1));
  stop.setAttribute("cy", point.y.toFixed(1));
}

applyLanguage(pickLanguage());
