import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
const out = process.env.UI_CAPTURE_DIR || 'artifacts/ui-polish';
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ channel: "chrome" });
const page = await browser.newPage();
await page.route('**/api/auth/status', r => r.fulfill({json:{loggedIn:false}}));
await page.route('**/api/config/vendors', r => r.fulfill({json:{vendors:[]}}));
await page.route('**/api/albums/extract', r => r.fulfill({json:{albums:[]}}));
await page.route('**/api/playlist?**', r => r.fulfill({json:{playlistName:'Sunday listening',totalTracks:4,offset:0,nextOffset:4,hasMore:false,tracks:['Blue Train','Harvest Moon','Kind of Blue','In a Silent Way'].map((name,i)=>({spotifyTrackId:`track-${i}`,trackName:name,artist:['John Coltrane','Neil Young','Miles Davis','Miles Davis'][i],album:name,releaseYear:[1958,1992,1959,1969][i],coverUrl:null,discogsAlbumUrl:'https://www.discogs.com/master/123',discogsMatch:{url:'https://www.discogs.com/master/123',matchType:'EXACT_MASTER',confidence:'HIGH',source:'DISCOGS_CATALOG',reason:'Matched artist and album metadata; choose a pressing.',vinylFormatConfirmed:false}}))}}));
for (const theme of ['light','dark']) {
 for (const [size,width,height] of [['desktop',1440,1000],['mobile',390,844]]) {
  await page.setViewportSize({width,height});
  await page.goto('http://127.0.0.1:8888');
  await page.evaluate(t=>{localStorage.setItem('vinylmatch_theme',t); document.documentElement.dataset.theme=t;},theme);
  for(const [name,path] of [['home','/'],['playlist','/playlist.html?id=fixture'],['about','/about.html']]) {
   await page.goto(`http://127.0.0.1:8888${path}`);
   await page.evaluate(t=>document.documentElement.dataset.theme=t,theme);
   await page.locator('#header .logo').waitFor();
   await page.locator('#theme-toggle[aria-pressed]').waitFor();
   if(name==='playlist') await page.getByText('Sunday listening').waitFor();
   await page.evaluate(async () => {
    await document.fonts.ready;
    for (const img of document.images) img.loading = "eager";
    await Promise.race([Promise.all([...document.images].map(img => img.decode().catch(() => {}))), new Promise(resolve => setTimeout(resolve, 5000))]);
   });
   await page.screenshot({path:`${out}/${name}-${theme}-${size}.png`,fullPage:true});
   if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)) throw Error(`Horizontal overflow: ${name} ${size}`);
  }
 }
}
await browser.close();
