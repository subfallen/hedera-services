import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import solc from 'solc';

const here = path.dirname(fileURLToPath(import.meta.url));
const sourceName = 'LocalInstantSwapReferencePools.sol';
const input = {
  language: 'Solidity', sources: {[sourceName]: {content: fs.readFileSync(path.join(here, sourceName), 'utf8')}},
  settings: {optimizer: {enabled: true, runs: 200}, evmVersion: 'paris', metadata: {bytecodeHash: 'none'},
    outputSelection: {'*': {'*': ['abi', 'evm.bytecode.object', 'evm.deployedBytecode.object']}}}
};
const output = JSON.parse(solc.compile(JSON.stringify(input)));
for (const error of output.errors ?? []) if (error.severity === 'error') throw new Error(error.formattedMessage);
const sha256 = hex => createHash('sha256').update(Buffer.from(hex, 'hex')).digest('hex');
const word = value => BigInt.asUintN(256, BigInt(value)).toString(16).padStart(64, '0');
const abi = (...values) => '0x' + values.map(word).join('');
const specifications = [
  ['PLEX', 'LocalInstantSwapPlexUsdc', 777782, 666666, 6, '0.10', 20005, 666666, '250541448375047931186413801569', 23027, 21780, 24240],
  ['WETH', 'LocalInstantSwapWethUsdc', 777783, 20001, 6, '2000', 20001, 20005, '3543191142285914205922034323214', 76012, 74760, 77220],
  ['WBTC', 'LocalInstantSwapWbtcUsdc', 777784, 20000, 7, '100000', 20000, 20005, '7922816251426433759354395033600', 92108, 90900, 93360],
  ['WHBAR', 'LocalInstantSwapWhbarUsdc', 777785, 777777, 8, '0.10', 20005, 777777, '2505414483750479311864138015696', 69081, 67860, 70320],
];
const files = new Map();
const principal = output.contracts[sourceName].LocalInstantSwapPrincipal;
files.set(path.join(here, '..', 'LocalInstantSwapPrincipal.bin'), principal.evm.bytecode.object + '\n');
const pools = specifications.map(([symbol, contract, contractNum, tokenNum, decimals, usdPrice, token0, token1, sqrtPriceX96, tick, tickLower, tickUpper]) => {
  const compiled = output.contracts[sourceName][contract];
  const initcode = compiled.evm.bytecode.object;
  const runtime = compiled.evm.deployedBytecode.object;
  files.set(path.join(here, '..', contract + '.bin'), initcode + '\n');
  const liquidity = '1000000000000000';
  const tickBitmap = {};
  const ticks = {};
  for (const [boundary, net] of [[tickLower, BigInt(liquidity)], [tickUpper, -BigInt(liquidity)]]) {
    const compressed = boundary / 60;
    const bitmapWord = Math.floor(compressed / 256);
    const bit = compressed - bitmapWord * 256;
    tickBitmap[bitmapWord] = abi(BigInt(tickBitmap[bitmapWord] ?? 0) | (1n << BigInt(bit)));
    ticks[boundary] = abi(liquidity, net, 0, 0, 0, 0, 0, 1);
  }
  return {symbol, contract, contractId: '0.0.' + contractNum, tokenId: '0.0.' + tokenNum, decimals, usdPrice,
    token0: '0.0.' + token0, token1: '0.0.' + token1, fee: 1500, tickSpacing: 60, sqrtPriceX96, tick,
    liquidity, tickLower, tickUpper, runtimeBytecode: '0x' + runtime, runtimeSha256: sha256(runtime),
    initcodeSha256: sha256(initcode), creationMemo: 'Local Instant Swap immutable reference ' + symbol,
    responses: {'0x0dfe1681': abi(token0), '0xd21220a7': abi(token1), '0xddca3f43': abi(1500),
      '0x3850c7bd': abi(sqrtPriceX96, tick, 0, 1, 1, 0, 1), '0x1a686502': abi(liquidity), '0xd0c93a7c': abi(60)},
    tickBitmap, ticks, emptyBitmapResponse: abi(0), emptyTickResponse: abi(0,0,0,0,0,0,0,0)};
});
const manifest = {version: 1, network: 'local-hedera', ledgerId: '03', chainId: 298,
  description: 'Immutable synthetic reference data; not funded AMM liquidity or swap execution.',
  compiler: solc.version(), compilerSettings: input.settings,
  principalAccountId: '0.0.888881',
  principalPublicKeyType: 'ED25519',
  principalPublicKeyHex: 'fe38cd4050d48585733961ac69578b69d8290950e36c09dc7f509574aa40a617',
  principalRuntimeBytecode: '0x' + principal.evm.deployedBytecode.object,
  principalRuntimeSha256: sha256(principal.evm.deployedBytecode.object),
  customerAccountIds: Array.from({length: 6}, (_, i) => '0.0.' + (888882 + i)),
  customerPublicKeyType: 'ECDSA_SECP256K1',
  customerPublicKeyHex: '03760246f2c3e01e7c4c818c0f499dce566df2d6a0ac3371afd63a79d935680831',
  quoteTokenId: '0.0.20005', quoteTokenDecimals: 6, pools};
files.set(path.join(here, 'fixtures.json'), JSON.stringify(manifest, null, 2) + '\n');
for (const [file, content] of files) {
  if (process.argv.includes('--check')) {
    if (!fs.existsSync(file) || fs.readFileSync(file, 'utf8') !== content) throw new Error('Regenerate stale fixture: ' + file);
  } else fs.writeFileSync(file, content);
}
console.log(process.argv.includes('--check') ? 'Fixture artifacts reproduce exactly.' : 'Generated four immutable local reference fixtures.');
