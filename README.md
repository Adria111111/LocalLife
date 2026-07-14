# LocalLife

LocalLife is an LBS based local life service platform upgraded from the original hm-dianping teaching project.

The original project already contains login, Redis cache, voucher seckill, follow feed, shop detail, and blog modules. This upgrade keeps that foundation and focuses on making the shop business closer to a real Dianping/Meituan style scenario:

- replace teaching shop rows with real POI style merchant data;
- store merchant longitude and latitude in MySQL `tb_shop`;
- build Redis GEO indexes by shop type;
- query nearby shops through `/shop/of/type?typeId=1&current=1&x=121.4737&y=31.2304`;
- return merchant distance, address, phone, opening hours, and other local life fields.

## Upgrade Steps

1. Import the original schema and data:

```sql
source src/main/resources/db/hmdp.sql;
```

2. Apply the LocalLife POI upgrade:

```sql
source src/main/resources/db/locallife_upgrade.sql;
```

3. Start MySQL and Redis, then run the Spring Boot app.

Before startup, provide local credentials through environment variables rather than committing them:

```powershell
$env:MYSQL_PASSWORD="your-mysql-password"
$env:AMAP_WEB_API_KEY="your-amap-web-service-key"
$env:ALIBABA_CLOUD_ACCESS_KEY_ID="your-access-key-id"
$env:ALIBABA_CLOUD_ACCESS_KEY_SECRET="your-access-key-secret"
```

4. Run `HmDianPingApplicationTests.loadShopGeoData()` once to rebuild Redis GEO data from `tb_shop`.

5. Query nearby shops:

```http
GET /shop/of/type?typeId=1&current=1&x=121.4737&y=31.2304
```

If `x` and `y` are omitted, the API keeps the original type based pagination behavior.

## Frontend Location Integration

The frontend only needs to pass browser location to the existing shop type API:

```javascript
navigator.geolocation.getCurrentPosition(pos => {
  params.x = pos.coords.longitude;
  params.y = pos.coords.latitude;
});
```

## Interview Pitch

The original project used teaching merchant data. I upgraded the business data source to POI style real merchant data, stored coordinates in MySQL, built Redis GEO spatial indexes by merchant category, and used browser geolocation to query nearby shops based on the user's current position. This turns the course demo into a more realistic local life LBS scenario.
