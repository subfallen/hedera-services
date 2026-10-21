import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import {createEVM} from '@ethereumjs/evm';
import {createAddressFromString} from '@ethereumjs/util';

const manifest = JSON.parse(fs.readFileSync(new URL('fixtures.json', import.meta.url), 'utf8'));
assert.equal(manifest.principalFunding.source, 'APPLICATION_BOT_FUNDING_WORKFLOW');
assert.equal(manifest.principalFunding.targetUsdPerToken, '1000');
assert.equal(manifest.principalFunding.genesisNativeHbarTinybar, '0');
assert.equal(manifest.principalFunding.targetNativeHbarTinybar, '1000000000000');
assert.equal(manifest.customerInitialNativeHbarTinybar, '1000000000000');
assert.equal(manifest.nativeHbar.referenceAuthority,
  'ISN1|298|0|777777|8|20005|6|777785|20005|777777|1500|1|WHBAR_TINYBAR_1_TO_1');
assert.equal(manifest.nativeHbar.tokenId, '0.0.0');
assert.equal(manifest.nativeHbar.decimals, 8);
assert.equal(BigInt(manifest.principalFunding.targetNativeHbarTinybar) * 100000n / (10n ** 8n), 1000000000n);
assert.deepEqual(manifest.principalFunding.genesisTokenBalances,
  {'0.0.20000': '0', '0.0.20001': '0', '0.0.20005': '0', '0.0.666666': '0'});
assert.deepEqual(manifest.principalFunding.targetTokenBalances, manifest.customerInitialTokenBalances);
for (const [token, decimals, microUsdPrice] of [[20000,7,100000000000n], [20001,6,2000000000n], [20005,6,1000000n], [666666,6,100000n]]) {
  const atoms = BigInt(manifest.principalFunding.targetTokenBalances['0.0.' + token]);
  assert.equal(atoms * microUsdPrice / (10n ** BigInt(decimals)), 1000000000n);
}
const dockerfile = fs.readFileSync(new URL('../Dockerfile', import.meta.url), 'utf8');
const dockerignore = fs.readFileSync(new URL('../.dockerignore', import.meta.url), 'utf8').split(/\r?\n/);
for (const contract of ['LocalInstantSwapPrincipal', ...manifest.pools.map(pool => pool.contract)]) {
  assert(dockerfile.includes(`COPY ${contract}.bin /app/${contract}.bin`), 'Missing image artifact ' + contract);
  assert(dockerignore.includes('!' + contract + '.bin'), 'Excluded image artifact ' + contract);
}
for (const file of ['application.properties', 'settings.txt']) {
  assert(dockerfile.includes('COPY hedera-node/configuration/dev/' + file + ' '), 'Missing dev config ' + file);
  assert(dockerignore.includes('!hedera-node/configuration/dev/' + file), 'Excluded dev config ' + file);
}
const evm = await createEVM();
const bytes = hex => Buffer.from(hex.replace(/^0x/, ''), 'hex');
const word = n => BigInt.asUintN(256, BigInt(n)).toString(16).padStart(64, '0');
let calls = 0;
async function run(code, data = '0x', extra = {}) {
  ++calls;
  return evm.runCode({code: bytes(code), data: bytes(data), gasLimit: 1_000_000n, ...extra});
}
async function expectCall(code, data, expected) {
  const result = await run(code, data);
  assert.equal(result.exceptionError, undefined, data);
  assert.equal('0x' + Buffer.from(result.returnValue).toString('hex'), expected, data);
}
for (const pool of manifest.pools) {
  const runtime = pool.runtimeBytecode;
  assert.equal(createHash('sha256').update(bytes(runtime)).digest('hex'), pool.runtimeSha256);
  const initcode = fs.readFileSync(new URL('../' + pool.contract + '.bin', import.meta.url), 'utf8').trim();
  assert.equal(createHash('sha256').update(bytes(initcode)).digest('hex'), pool.initcodeSha256);
  const deployment = await run(initcode);
  assert.equal(deployment.exceptionError, undefined);
  assert.equal('0x' + Buffer.from(deployment.returnValue).toString('hex'), runtime);
  for (const [selector, expected] of Object.entries(pool.responses)) await expectCall(runtime, selector, expected);
  for (const [wordIndex, expected] of Object.entries(pool.tickBitmap)) {
    await expectCall(runtime, '0x5339c296' + word(wordIndex), expected);
  }
  for (const [tick, expected] of Object.entries(pool.ticks)) {
    await expectCall(runtime, '0xf30dba93' + word(tick), expected);
  }
  for (const tick of [-887272, -1, 0, pool.tick, 887272]) {
    await expectCall(runtime, '0xf30dba93' + word(tick), pool.emptyTickResponse);
  }
  for (const bitmapWord of [-32768, -1, 0, 32767]) {
    await expectCall(runtime, '0x5339c296' + word(bitmapWord), pool.emptyBitmapResponse);
  }
  for (const invalid of ['0xffffffff', '0xf30dba93', '0x5339c296', '0x5339c296' + word(32768), '0xf30dba93' + word(8388608)]) {
    assert.notEqual((await run(runtime, invalid)).exceptionError, undefined, invalid);
  }
  assert.notEqual((await run(runtime, '0x0dfe1681', {value: 1n})).exceptionError, undefined);
  const price = BigInt(pool.sqrtPriceX96);
  // The encoded square root is exactly floor(sqrt(raw token1/token0 price) * 2^96).
  const numerator = {PLEX: 10n, WETH: 2000n, WBTC: 10000n, WHBAR: 1000n}[pool.symbol];
  assert(price * price <= numerator * (1n << 192n));
  assert((price + 1n) * (price + 1n) > numerator * (1n << 192n));
  assert.equal(Math.floor(Math.log(Number(numerator)) / Math.log(1.0001)), pool.tick);
  assert(pool.tickLower < pool.tick && pool.tickUpper > pool.tick);
  assert.equal(pool.tickLower % pool.tickSpacing, 0);
  assert.equal(pool.tickUpper % pool.tickSpacing, 0);
  assert.equal(BigInt.asIntN(256, BigInt('0x' + pool.ticks[pool.tickLower].slice(66,130))), BigInt(pool.liquidity));
  assert.equal(BigInt.asIntN(256, BigInt('0x' + pool.ticks[pool.tickUpper].slice(66,130))), -BigInt(pool.liquidity));
}
const principalInit = fs.readFileSync(new URL('../LocalInstantSwapPrincipal.bin', import.meta.url), 'utf8').trim();
// The principal is created with zero balance; all capital is funded through the application afterward.
const principalDeployment = await run(principalInit);
assert.equal(principalDeployment.exceptionError, undefined);
assert.equal('0x' + Buffer.from(principalDeployment.returnValue).toString('hex'), manifest.principalRuntimeBytecode);
const address = createAddressFromString('0x' + BigInt(888881).toString(16).padStart(40, '0'));
const manager = await run(manifest.principalRuntimeBytecode, '0x481c6a75', {to: address});
assert.equal(manager.exceptionError, undefined);
assert.equal('0x' + Buffer.from(manager.returnValue).toString('hex'), '0x' + word(888881));
console.log(`Passed ${calls} EVM executions: deployed runtimes, ABI, signed ticks, bitmap boundaries, prices, and principal manager.`);
