const { chromium } = require('playwright');
(async()=>{
 const browser=await chromium.launch({headless:true});
 const page=await browser.newPage({viewport:{width:430,height:1500},deviceScaleFactor:1});
 await page.goto('http://127.0.0.1:8066/',{waitUntil:'networkidle'});
 await page.screenshot({path:'C:/NOMADIC_TIDE/transfer_ai/preview-live-now.png',fullPage:true});
 await browser.close();
})();
