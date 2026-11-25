import { readFileSync } from "node:fs";
import { cityPagePath, translations } from "../landing/locales.js";

const read = path => JSON.parse(readFileSync(new URL(path, import.meta.url), "utf8"));
const routes = read("./zugdidi-routes.json");
const legs = read("./zugdidi-trip-stops.json");
const names = read("../transitBff/src/main/resources/transit/zugdidi/names.json");
export const zugdidiTrips = ["24", "23", "1"];

const copy = {
  ru: ["Как добраться", "Открыть маршрут на карте", "Все остановки",
    ["№ 24 · Из Зугдиди к морю в Анаклию", "Садитесь у железнодорожного вокзала и выходите в Анаклии. Обратный автобус идёт через вокзал до автостанции Зугдиди."],
    ["№ 23 · В село Цаиши", "От железнодорожного вокзала езжайте в сторону Цацхви. Выходите на промежуточной остановке «Цаиши». Обратно из Цаиши автобус идёт до автостанции Зугдиди."],
    ["№ 1 · От вокзала до рынка", "У железнодорожного вокзала садитесь в автобус в сторону улицы Мераба Барамия. После остановки «Улица Шота Руставели, 102» выходите на остановке «Рынок»."]],
  th: ["วิธีเดินทาง", "เปิดเส้นทางบนแผนที่", "ป้ายทั้งหมด",
    ["รถประจำทางสาย 24 · ซุกดิดี ไปชายทะเลใน Anaklia", "ขึ้นรถไฟที่สถานีรถไฟแล้วลงที่อานาเคลีย รถบัสขากลับจะผ่านสถานีรถไฟและสิ้นสุดที่สถานีขนส่ง ซุกดิดี"],
    ["รถบัส 23 · หมู่บ้าน Tsaishi", "จากสถานีรถไฟ มุ่งหน้าไปยัง Tsatskhvi และลงที่ป้ายกลาง Tsaishi รถบัสขากลับจาก Tsaishi สิ้นสุดที่สถานีขนส่ง ซุกดิดี"],
    ["รถโดยสารประจำทาง 1 · สถานีรถไฟไปตลาด", "ที่สถานีรถไฟ ให้ขึ้นรถไปยังถนน Merab Baramia ลงที่ Market หลังเลขที่ 102 Shota Rustaveli Street"]],
  en: ["How to get there", "Open route on the map", "All stops",
    ["Bus 24 · Zugdidi to the seaside in Anaklia", "Board at the railway station and get off in Anaklia. The return bus passes the railway station and terminates at Zugdidi Bus Station."],
    ["Bus 23 · Tsaishi village", "From the railway station, travel towards Tsatskhvi and get off at the intermediate stop Tsaishi. The return bus from Tsaishi terminates at Zugdidi Bus Station."],
    ["Bus 1 · Railway station to the market", "At the railway station, board towards Merab Baramia Street. Get off at Market, just after 102 Shota Rustaveli Street."]],
  ka: ["როგორ მივიდეთ", "მარშრუტის ნახვა რუკაზე", "ყველა გაჩერება",
    ["№24 · ზუგდიდიდან ანაკლიაში, ზღვის სანაპიროზე", "ჩაჯექით რკინიგზის სადგურთან და ჩამოდით ანაკლიაში. უკან ავტობუსი რკინიგზის სადგურის გავლით ზუგდიდის ავტოსადგურამდე მიდის."],
    ["№23 · სოფელ ცაიშში", "რკინიგზის სადგურიდან წადით ცაცხვის მიმართულებით და ჩამოდით შუალედურ გაჩერებაზე „ცაიში“. უკან ცაიშიდან ავტობუსი ზუგდიდის ავტოსადგურამდე მიდის."],
    ["№1 · სადგურიდან ბაზრამდე", "რკინიგზის სადგურთან ჩაჯექით მერაბ ბარამიას ქუჩისკენ მიმავალ ავტობუსში. შოთა რუსთაველის ქუჩის №102-ის შემდეგ ჩამოდით გაჩერებაზე „ბაზარი“."]],
  de: ["So kommen Sie ans Ziel", "Linie auf der Karte öffnen", "Alle Haltestellen",
    ["Bus 24 · Von Sugdidi ans Meer nach Anaklia", "Steigen Sie am Bahnhof ein und in Anaklia aus. Zurück fährt der Bus über den Bahnhof bis zum Busbahnhof Sugdidi."],
    ["Bus 23 · Ins Dorf Tsaishi", "Fahren Sie vom Bahnhof Richtung Tsatskhvi und steigen Sie unterwegs in Tsaishi aus. Auf der Rückfahrt endet der Bus am Busbahnhof Sugdidi."],
    ["Bus 1 · Vom Bahnhof zum Markt", "Nehmen Sie am Bahnhof den Bus Richtung Merab-Baramia-Straße. Steigen Sie am Markt aus, direkt nach der Haltestelle Schota-Rustaweli-Straße 102."]],
  fr: ["Comment s’y rendre", "Voir la ligne sur la carte", "Tous les arrêts",
    ["Bus nº 24 · De Zougdidi à la mer à Anaklia", "Montez à la gare ferroviaire et descendez à Anaklia. Au retour, le bus passe par la gare ferroviaire avant de rejoindre la gare routière de Zougdidi."],
    ["Bus nº 23 · Village de Tsaïchi", "À la gare ferroviaire, prenez le bus vers Tsatskhvi et descendez à l’arrêt intermédiaire de Tsaïchi. Au retour, le bus termine son trajet à la gare routière de Zougdidi."],
    ["Bus nº 1 · De la gare au marché", "À la gare ferroviaire, prenez le bus vers la rue Merab-Baramia. Descendez au marché, juste après l’arrêt du 102 rue Chota-Roustavéli."]],
  "zh-Hans": ["交通指南", "在地图上查看线路", "全部站点",
    ["24路 · 从祖格迪迪到阿纳克利亚海滨", "在火车站上车，到阿纳克利亚下车。返程公交车经过火车站，终点为祖格迪迪汽车站。"],
    ["23路 · 前往察伊希村", "从火车站乘坐开往察茨赫维的公交车，在中途的察伊希站下车。返程车终点为祖格迪迪汽车站。"],
    ["1路 · 从火车站到市场", "在火车站乘坐开往梅拉布·巴拉米亚街方向的公交车，经过鲁斯塔韦利街102号后，在市场站下车。"]],
};
export const zugdidiTripPath = (number, language) => cityPagePath(`zugdidi-bus-${number}`, language);
function ride(number, language) {
  return copy[language][3 + zugdidiTrips.indexOf(number)];
}
export function renderZugdidiTrips(language) {
  return `<section class="wrap batumi-trips" aria-labelledby="how-to-get"><h2 id="how-to-get">${copy[language][0]}</h2>
      <div class="trip-grid">${zugdidiTrips.map(n => {
        const [title, description] = ride(n, language);
        return `<article><h3><a href="${zugdidiTripPath(n, language)}">${title}</a></h3><p>${description}</p></article>`;
      }).join("\n")}</div></section>`;
}
export function zugdidiTripPage(number, language, escape) {
  const [title, description] = ride(number, language);
  const [, map, allStops] = copy[language];
  const route = routes.find(r => r.number === number);
  const name = key => escape(names[key][language === "zh-Hans" ? "zh" : language]);
  const directions = legs[number].map(stops => `<section><h2>${name(stops[0])} → ${name(stops.at(-1))}</h2>
    <details><summary>${allStops} (${stops.length})</summary><ol>${stops.map(s => `<li>${name(s)}</li>`).join("")}</ol></details></section>`).join("\n");
  const main = `<div class="wrap route-detail"><a href="${cityPagePath("zugdidi", language)}#how-to-get">← ${escape(translations[language].zugdidi)}</a>
    <h1>${title}</h1><p>${description}</p><a class="button city-guide__cta" data-analytics-platform="web" href="https://app.geotransit.com.ge/?city=zugdidi&amp;route=${encodeURIComponent(route.id)}">${map}</a>
    <div class="trip-directions">${directions}</div></div>`;
  return { title: `${title} | Georgia Transit`, description, main };
}
