import { readFileSync } from "node:fs";
import { cityPagePath, translations } from "../landing/locales.js";

// Both directions from the Transit BFF, checked 2026-11-25. Preserve stop codes
// and operator spellings so riders can match the list to signs and timetables.
const stops = JSON.parse(readFileSync(new URL("./kutaisi-trip-stops.json", import.meta.url), "utf8"));
export const kutaisiTrips = ["airport-shuttle", "bus-7", "bus-3"];
const airportFare = "https://kutaisi.travel/en/10093/shuttle-buses-will-serve-passengers-from-kutaisi-international-airport-to-the-city-center/";
const copy = {
  ru: {
    heading: "Как добраться", stops: "Все остановки", map: "Открыть маршрут на карте", schedule: "Расписание шаттла", fare: "Тариф и оплата",
    airport: "Между центром Кутаиси и аэропортом", shuttle: "Шаттл аэропорта",
    outbound: "В аэропорт садитесь на шаттл у Туристического центра Кутаиси (Kutaisi Tourism Center). Перед выездом сверьте время отправления из города с расписанием аэропорта.",
    inbound: "В город шаттл отправляется от выхода из зала прилёта. До Туристического центра около 40 минут по расписанию.",
    price: "Kutaisi Travel указывает 5 ₾ за поездку в одну сторону. Оплата банковской картой.",
    sataplia: "№ 7 · До пещеры Сатаплия", rioni: "№ 3 · До железнодорожной станции Риони",
    ends: [["Красный мост", "Пещера Сатаплия"], ["ул. З. Палиашвили, 13", "Железнодорожная станция Риони"]],
  },
  en: {
    heading: "How to get there", stops: "All stops", map: "Open route on the map", schedule: "Shuttle timetable", fare: "Fare and payment",
    airport: "Kutaisi city centre to the airport", shuttle: "Airport shuttle",
    outbound: "For the airport, board at the Kutaisi Tourism Center. Check the airport timetable for departures from the city before you leave.",
    inbound: "For the city centre, board outside the arrivals hall. The scheduled journey to the Tourism Center takes about 40 minutes.",
    price: "Kutaisi Travel lists a one-way fare of GEL 5, payable by bank card.",
    sataplia: "Bus 7 · Sataplia Cave", rioni: "Bus 3 · Rioni railway station",
    ends: [["Red Bridge", "Sataplia Cave"], ["13 Z. Paliashvili Street", "Rioni railway station"]],
  },
  ka: {
    heading: "როგორ მივიდეთ", stops: "ყველა გაჩერება", map: "მარშრუტის ნახვა რუკაზე", schedule: "შატლის განრიგი", fare: "ტარიფი და გადახდა",
    airport: "ქუთაისის ცენტრიდან აეროპორტამდე", shuttle: "აეროპორტის შატლი",
    outbound: "აეროპორტისკენ მიმავალ შატლში ქუთაისის ტურიზმის ცენტრთან ჩაჯექით. გასვლამდე ქალაქიდან გამგზავრების დრო აეროპორტის განრიგში გადაამოწმეთ.",
    inbound: "ცენტრისკენ მიმავალი შატლი ჩამოფრენის დარბაზის გასასვლელიდან გადის. განრიგით ტურიზმის ცენტრამდე მგზავრობას დაახლოებით 40 წუთი სჭირდება.",
    price: "Kutaisi Travel-ის ინფორმაციით, ერთი გზის საფასური 5 ლარია. გადახდა საბანკო ბარათით შეგიძლიათ.",
    sataplia: "№7 · სათაფლიის მღვიმე", rioni: "№3 · რკინიგზის სადგური რიონი",
    ends: [["წითელი ხიდი", "სათაფლიის მღვიმე"], ["ზ. ფალიაშვილის ქუჩა №13", "რკინიგზის სადგური რიონი"]],
  },
  de: {
    heading: "So kommen Sie ans Ziel", stops: "Alle Haltestellen", map: "Linie auf der Karte öffnen", schedule: "Shuttle-Fahrplan", fare: "Fahrpreis und Bezahlung",
    airport: "Vom Zentrum Kutaissis zum Flughafen", shuttle: "Flughafenshuttle",
    outbound: "Zum Flughafen fährt der Shuttle am Kutaisi Tourism Center ab. Prüfen Sie vor der Fahrt die Abfahrtszeit für diese Richtung im Flughafenfahrplan.",
    inbound: "Ins Zentrum fährt der Shuttle vor dem Ausgang der Ankunftshalle ab. Bis zum Tourismuszentrum sind es laut Fahrplan rund 40 Minuten.",
    price: "Kutaisi Travel nennt 5 GEL für die einfache Fahrt. Sie können mit Bankkarte bezahlen.",
    sataplia: "Bus 7 · Sataplia-Höhle", rioni: "Bus 3 · Bahnhof Rioni",
    ends: [["Rote Brücke", "Sataplia-Höhle"], ["Z. Paliaschwili-Straße 13", "Bahnhof Rioni"]],
  },
  fr: {
    heading: "Comment s’y rendre", stops: "Tous les arrêts", map: "Voir la ligne sur la carte", schedule: "Horaires de la navette", fare: "Tarif et paiement",
    airport: "Du centre de Koutaïssi à l’aéroport", shuttle: "Navette de l’aéroport",
    outbound: "Pour l’aéroport, prenez la navette au Kutaisi Tourism Center. Avant de partir, consultez les horaires de l’aéroport dans le sens ville–aéroport.",
    inbound: "Pour le centre-ville, montez à la sortie du hall des arrivées. Comptez environ 40 minutes jusqu’au centre touristique selon l’horaire publié.",
    price: "Kutaisi Travel indique 5 GEL pour un aller simple, payable par carte bancaire.",
    sataplia: "Bus nº 7 · Grotte de Sataplia", rioni: "Bus nº 3 · Gare de Rioni",
    ends: [["Pont Rouge", "Grotte de Sataplia"], ["13 rue Z. Paliachvili", "Gare de Rioni"]],
  },
  "zh-Hans": {
    heading: "交通指南", stops: "全部站点", map: "在地图上查看线路", schedule: "机场班车时刻表", fare: "票价与支付",
    airport: "从库塔伊西市中心到机场", shuttle: "机场班车",
    outbound: "前往机场，请在库塔伊西旅游中心（Kutaisi Tourism Center）乘车。出发前请查看机场公布的市区往机场方向的班次。",
    inbound: "前往市中心，请在到达大厅出口乘车。按时刻表，到旅游中心约需40分钟。",
    price: "据 Kutaisi Travel，单程票价为5拉里，可使用银行卡支付。",
    sataplia: "7路 · 萨塔普利亚洞穴", rioni: "3路 · 里奥尼火车站",
    ends: [["红桥", "萨塔普利亚洞穴"], ["帕利亚什维利街13号", "里奥尼火车站"]],
  },
};
export function kutaisiTripPath(slug, language) {
  return cityPagePath(`kutaisi-${slug}`, language);
}
function tripInfo(slug, c) {
  if (slug === "airport-shuttle") return { title: c.airport, description: c.outbound };
  const number = slug.slice(4);
  const ends = c.ends[number === "7" ? 0 : 1];
  const route = stops[number];
  const outbound = route.legs[0];
  const description = `${ends[0]} (${outbound[0].code}) → ${ends[1]} (${outbound.at(-1).code})`;
  return { title: number === "7" ? c.sataplia : c.rioni, description, ends, route };
}
export function renderKutaisiTrips(language) {
  const c = copy[language];
  return `<section class="wrap batumi-trips" aria-labelledby="how-to-get"><h2 id="how-to-get">${c.heading}</h2>
      <div class="trip-grid">${kutaisiTrips.map(slug => {
        const { title, description } = tripInfo(slug, c);
        return `<article><h3><a href="${kutaisiTripPath(slug, language)}">${title}</a></h3><p>${description}</p></article>`;
      }).join("\n")}</div></section>`;
}
export function kutaisiTripPage(slug, language, esc) {
  const c = copy[language];
  const back = translations[language].kutaisi;
  const { title, description, ends, route } = tripInfo(slug, c);
  const sourceLang = language === "ka" ? "ka" : "en";
  const details = route
    ? `<a class="button city-guide__cta" data-analytics-platform="web" href="https://app.geotransit.com.ge/?city=kutaisi&amp;route=${encodeURIComponent(route.id)}">${c.map}</a>
       <div class="trip-directions">${route.legs.map((leg, i) => `<section><h2>${ends[i]} → ${ends[1-i]}</h2>
         <details><summary>${c.stops} (${leg.length})</summary><ol>${leg.map(stop => `<li><span lang="${sourceLang}">${esc(stop[sourceLang])}</span> <small>· ${esc(stop.code)}</small></li>`).join("")}</ol></details></section>`).join("\n")}</div>`
    : `<p>${c.inbound}</p><p>${c.price} <a href="${airportFare}">${c.fare} · Kutaisi Travel</a></p>
       <a class="button city-guide__cta" href="https://kutaisi.aero/${sourceLang}/transport/shuttle-bus">${c.schedule}</a>`;
  return { title: `${title} | Geo Transit`, description, main: `<div class="wrap route-detail">
      <a href="${cityPagePath("kutaisi", language)}#how-to-get">← ${esc(back)}</a>
      <h1>${title}</h1><p>${description}</p>${details}</div>` };
}
