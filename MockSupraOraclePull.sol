// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

interface ISupraOraclePull {
    struct PriceInfo {
        uint256[] pairs;
        uint256[] prices;
        uint256[] timestamp;
        uint256[] decimal;
        uint256[] round;
    }

    function verifyOracleProofV2(bytes calldata _bytesProof)
        external
        returns (PriceInfo memory);
}

error MockSupraRevert();
error MockBadProof();
error MockUnknownMode(uint256 mode);

contract MockSupraOraclePull is ISupraOraclePull {
    // proof layout:
    //   [0:32)  uint256 mode
    //   [32:]   ABI-encoded PriceInfo RETURN DATA (exactly what a real oracle would return)
    function verifyOracleProofV2(bytes calldata proof)
        external
        override
        returns (PriceInfo memory)
    {
        if (proof.length < 32) revert MockBadProof();

        uint256 mode;
        assembly {
            mode := calldataload(proof.offset)
        }

        if (mode == 1) revert MockSupraRevert();

        if (mode == 0) {
            // Echo the remaining bytes as the function's return data.
            uint256 len = proof.length - 32;

            assembly {
                // free memory pointer
                let ptr := mload(0x40)

                // copy payload (proof after the first word) into memory
                calldatacopy(ptr, add(proof.offset, 32), len)

                // return payload as raw return data
                return(ptr, len)
            }
        }

        revert MockUnknownMode(mode);
    }
}

