import { readFileSync } from "node:fs";
import { cityPagePath } from "../landing/locales.js";

const read = path => JSON.parse(readFileSync(new URL(path, import.meta.url), "utf8"));
const catalog = read("./batumi-routes.json");
const stops = Object.values(read("../transitBff/src/main/resources/transit/batumi/stop-names.json"));
const byCode = new Map(stops.map(stop => [stop.code, stop]));

// Ordered BFF direction stops checked on 2026-11-25. Each technical direction
// contains both legs, with the turning stop shared by the two lists.
const orders = read("./batumi-route-stop-order.json");
export const tripNumbers = Object.keys(orders);
const turns = { 10: "1452", 16: "1402" };

const copy = {
  ru: {
    heading: "Как добраться", bus: "Автобус №", city: "Батуми", map: "Открыть маршрут на карте", list: "Все остановки", highlights: "Основные остановки", back: "Автобусы Батуми",
    trips: ["Из аэропорта в центр", "Из центра до Ботанического сада", "Из Батуми в Сарпи"],
    rides: ["От аэропорта садитесь в № 10 в сторону Ботанического сада. В центре можно выйти у Драматического театра.", "У Драматического театра садитесь в № 10 в сторону сада. Выходите на улице Баги. Обратно в город автобус отправляется от остановки «Ботанический сад».", "На автовокзале Батуми садитесь в № 16 в сторону Сарпи. Можно также сесть на Тбилисской площади. Выходите на остановке «Сарпи»."],
    directions: ["К Ботаническому саду", "К аэропорту", "В Сарпи", "В Батуми"],
  },
  th: {
    heading: "วิธีเดินทาง", bus: "รถประจำทาง ", city: "บาตูมี", map: "เปิดเส้นทางบนแผนที่", list: "ป้ายทั้งหมด", highlights: "ป้ายหลัก", back: "รถโดยสารบาตูมี",
    trips: ["สนามบินสู่ใจกลางเมือง", "ใจกลางเมืองไปยังสวนพฤกษศาสตร์", "บาตูมีถึงซาร์ปี"],
    rides: ["ที่สนามบิน ให้นั่งรถบัสสาย 10 ไปทางสวนพฤกษศาสตร์ ลงที่โรงละครเพื่อเข้าสู่ใจกลางเมือง", "ที่โรงละคร ให้นั่งรถบัสสาย 10 ไปทางสวน ลงที่ถนนบากี สำหรับการเดินทางขากลับ ให้ขึ้นรถที่ป้ายสวนพฤกษศาสตร์", "ขึ้นรถบัสสาย 16 ไปทาง Sarpi ที่สถานีขนส่ง บาตูมี หรือขึ้นที่จัตุรัส Tbilisi ลงที่ซาร์ปี"],
    directions: ["ไปทางสวนพฤกษศาสตร์", "มุ่งหน้าสู่สนามบิน", "ถึงซาร์ปี้", "ถึงบาตูมี"],
  },
  en: {
    heading: "How to get there", bus: "Bus ", city: "Batumi", map: "Open route on the map", list: "All stops", highlights: "Key stops", back: "Batumi buses",
    trips: ["Airport to the city centre", "City centre to the Botanical Garden", "Batumi to Sarpi"],
    rides: ["At the airport, take bus 10 towards the Botanical Garden. Get off at the Drama Theatre for the city centre.", "At the Drama Theatre, take bus 10 towards the garden. Get off on Bagi Street. For the return ride, board at the Botanical Garden stop.", "Take bus 16 towards Sarpi at Batumi Bus Station or board at Tbilisi Square. Get off at Sarpi."],
    directions: ["Towards the Botanical Garden", "Towards the airport", "To Sarpi", "To Batumi"],
  },
  ka: {
    heading: "როგორ მივიდეთ", bus: "ავტობუსი №", city: "ბათუმი", map: "მარშრუტის ნახვა რუკაზე", list: "ყველა გაჩერება", highlights: "ძირითადი გაჩერებები", back: "ბათუმის ავტობუსები",
    trips: ["აეროპორტიდან ქალაქის ცენტრში", "ცენტრიდან ბოტანიკურ ბაღამდე", "ბათუმიდან სარფში"],
    rides: ["აეროპორტში ჩაჯექით №10 ავტობუსში ბოტანიკური ბაღის მიმართულებით. ქალაქის ცენტრში ჩამოდით დრამატულ თეატრთან.", "დრამატულ თეატრთან ჩაჯექით №10 ავტობუსში ბოტანიკური ბაღის მიმართულებით. ჩამოდით ბაღის ქუჩაზე. უკან დასაბრუნებლად ჩაჯექით გაჩერებაზე „ბოტანიკური ბაღი“.", "ბათუმის ავტოსადგურში ჩაჯექით №16 ავტობუსში სარფის მიმართულებით. ასვლა თბილისის მოედანზეც შეგიძლიათ. ჩამოდით სარფში."],
    directions: ["ბოტანიკური ბაღისკენ", "აეროპორტისკენ", "სარფისკენ", "ბათუმისკენ"],
  },
  de: {
    heading: "So kommen Sie ans Ziel", bus: "Bus ", city: "Batumi", map: "Linie auf der Karte öffnen", list: "Alle Haltestellen", highlights: "Wichtige Haltestellen", back: "Busse in Batumi",
    trips: ["Vom Flughafen ins Zentrum", "Vom Zentrum zum Botanischen Garten", "Von Batumi nach Sarpi"],
    rides: ["Nehmen Sie am Flughafen die Linie 10 Richtung Botanischer Garten. Für das Zentrum steigen Sie am Schauspielhaus aus.", "Nehmen Sie am Schauspielhaus die Linie 10 Richtung Botanischer Garten und steigen Sie an der Bagi-Straße aus. Die Rückfahrt beginnt an der Haltestelle Botanischer Garten.", "Nehmen Sie am Busbahnhof Batumi die Linie 16 Richtung Sarpi. Sie können auch am Tbilisi-Platz einsteigen. Steigen Sie in Sarpi aus."],
    directions: ["Richtung Botanischer Garten", "Richtung Flughafen", "Nach Sarpi", "Nach Batumi"],
  },
  fr: {
    heading: "Comment s’y rendre", bus: "Bus nº ", city: "Batoumi", map: "Voir la ligne sur la carte", list: "Tous les arrêts", highlights: "Principaux arrêts", back: "Bus de Batoumi",
    trips: ["De l’aéroport au centre-ville", "Du centre au jardin botanique", "De Batoumi à Sarpi"],
    rides: ["À l’aéroport, prenez le bus 10 vers le jardin botanique. Pour le centre-ville, descendez au théâtre dramatique.", "Au théâtre dramatique, prenez le bus 10 vers le jardin botanique et descendez rue Bagi. Pour le retour, montez à l’arrêt Jardin botanique.", "Prenez le bus 16 vers Sarpi à la gare routière de Batoumi ou sur la place de Tbilissi. Descendez à Sarpi."],
    directions: ["Vers le jardin botanique", "Vers l’aéroport", "Vers Sarpi", "Vers Batoumi"],
  },
  "zh-Hans": {
    heading: "交通指南", bus: "公交线路 ", city: "巴统", map: "在地图上查看线路", list: "全部站点", highlights: "主要站点", back: "巴统公交",
    trips: ["从机场到市中心", "从市中心到植物园", "从巴统到萨尔皮"],
    rides: ["在机场乘坐开往植物园方向的10路公交车，到市中心可在剧院站下车。", "在剧院站乘坐开往植物园方向的10路公交车，在巴吉街下车。返程请在植物园站上车。", "在巴统汽车站乘坐开往萨尔皮的16路公交车，也可在第比利斯广场上车，在萨尔皮站下车。"],
    directions: ["往植物园", "往机场", "往萨尔皮", "往巴统"],
  },
};

const trips = [
  { number: "10", stops: ["1452", "1002"] },
  { number: "10", stops: ["1002", "1801"] },
  { number: "16", stops: ["1463", "1402"] },
];
const keyStops = {
  10: [["1452", "1008", "1002", "1080", "1408", "1801"], ["1798", "1407", "1283", "1085", "1001", "1452"]],
  16: [["1463", "1219", "1095", "1105", "1950", "1402"], ["1402", "1359", "1102", "1092", "1222", "1232"]],
};
export function tripPath(number, language) {
  return cityPagePath(`batumi-bus-${number}`, language);
}
function stopLabel(code, lang, esc) {
  return `${esc(byCode.get(code).name[lang])} <small>· ${code}</small>`;
}
export function renderTrips(language, esc) {
  const c = copy[language];
  const lang = language === "zh-Hans" ? "zh" : language;
  return `<section class="wrap batumi-trips" aria-labelledby="how-to-get">
      <h2 id="how-to-get">${c.heading}</h2>
      <div class="trip-grid">${trips.map(({ number, stops }, i) => `<article>
        <h3><a href="${tripPath(number, language)}#${i === 2 ? "sarpi" : "garden"}">${c.trips[i]}</a></h3>
        <p>${c.rides[i]}</p><p class="trip-stops">${c.bus}${number} · ${stops.map(code => stopLabel(code, lang, esc)).join(" → ")}</p>
      </article>`).join("\n")}</div>
    </section>`;
}
export function routePage(number, language, esc) {
  const c = copy[language];
  const lang = language === "zh-Hans" ? "zh" : language;
  const route = catalog.find(r => r.number === number);
  const title = `${c.city} · ${c.bus}${number} · ${route.name[lang]}`;
  const outbound = number === "10" ? 0 : 2;
  const sequence = orders[number];
  const turn = sequence.indexOf(turns[number]);
  const first = sequence.slice(0, turn + 1);
  const second = sequence.slice(turn);
  const legs = number === "10" ? [second, first] : [first, second];
  const anchors = number === "10" ? ["garden", "airport"] : ["sarpi", "batumi"];
  const description = c.rides[outbound];
  const main = `<div class="wrap route-detail">
      <a href="${cityPagePath("batumi", language)}#how-to-get">← ${c.back}</a>
      <h1>${esc(title)}</h1>
      <p>${description}</p>
      <a class="button city-guide__cta" href="https://app.geotransit.com.ge/?city=batumi&amp;route=${encodeURIComponent(route.id)}" data-analytics-platform="web">${c.map}</a>
      <div class="trip-directions">${legs.map((codes, i) => `<section id="${anchors[i]}">
        <h2>${c.directions[outbound + i]}</h2><p>${c.highlights}</p>
        <ol>${keyStops[number][i].map(code => `<li>${stopLabel(code, lang, esc)}</li>`).join("")}</ol>
        <details><summary>${c.list} (${codes.length})</summary>
          <ol>${codes.map(code => `<li>${stopLabel(code, lang, esc)}</li>`).join("")}</ol>
        </details></section>`).join("\n")}</div>
    </div>`;
  return { title: `${title} | Georgia Transit`, description, main };
}
