// SPDX-License-Identifier: Apache-2.0
pragma solidity 0.8.37;

/// Same manager ABI as the application's SelfManager BOT. HAPI assigns its distinct admin key.
contract LocalInstantSwapPrincipal {
    function manager() external view returns (address) { return address(this); }
}

/// Local-only, immutable reference data. These contracts cannot execute swaps or hold liquidity.
/// Each concrete runtime contains its entire configuration, with no storage, admin or selfdestruct.
abstract contract LocalInstantSwapReferencePool {
    function config() internal pure virtual returns (uint160, uint160, uint160, int24, int24, int24);

    function token0() external pure returns (address) { (uint160 a,,,,,) = config(); return address(a); }
    function token1() external pure returns (address) { (,uint160 b,,,,) = config(); return address(b); }
    function fee() external pure returns (uint24) { return 1500; }
    function tickSpacing() external pure returns (int24) { return 60; }
    function liquidity() public pure returns (uint128) { return 1_000_000_000_000_000; }

    function slot0() external pure returns (uint160, int24, uint16, uint16, uint16, uint8, bool) {
        (,,uint160 price,int24 tick,,) = config();
        return (price, tick, 0, 1, 1, 0, true);
    }

    function tickBitmap(int16 word) external pure returns (uint256 bitmap) {
        (,,,,int24 lower,int24 upper) = config();
        int24 lowCompressed = lower / 60;
        int24 highCompressed = upper / 60;
        if (int16(lowCompressed >> 8) == word) bitmap |= uint256(1) << uint8(uint24(lowCompressed));
        if (int16(highCompressed >> 8) == word) bitmap |= uint256(1) << uint8(uint24(highCompressed));
    }

    function ticks(int24 tick) external pure returns (uint128, int128, uint256, uint256, int56, uint160, uint32, bool) {
        (,,,,int24 lower,int24 upper) = config();
        if (tick == lower) return (liquidity(), int128(liquidity()), 0, 0, 0, 0, 0, true);
        if (tick == upper) return (liquidity(), -int128(liquidity()), 0, 0, 0, 0, 0, true);
        return (0, 0, 0, 0, 0, 0, 0, false);
    }
}

contract LocalInstantSwapPlexUsdc is LocalInstantSwapReferencePool {
    function config() internal pure override returns (uint160, uint160, uint160, int24, int24, int24) {
        return (20005, 666666, 250541448375047931186413801569, 23027, 21780, 24240);
    }
}

contract LocalInstantSwapWethUsdc is LocalInstantSwapReferencePool {
    function config() internal pure override returns (uint160, uint160, uint160, int24, int24, int24) {
        return (20001, 20005, 3543191142285914205922034323214, 76012, 74760, 77220);
    }
}

contract LocalInstantSwapWbtcUsdc is LocalInstantSwapReferencePool {
    function config() internal pure override returns (uint160, uint160, uint160, int24, int24, int24) {
        return (20000, 20005, 7922816251426433759354395033600, 92108, 90900, 93360);
    }
}

contract LocalInstantSwapWhbarUsdc is LocalInstantSwapReferencePool {
    function config() internal pure override returns (uint160, uint160, uint160, int24, int24, int24) {
        return (20005, 777777, 2505414483750479311864138015696, 69081, 67860, 70320);
    }
}
