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

error MockBadProof();

contract MockSupraOraclePull is ISupraOraclePull {
    struct CommitteeFeed {
        uint32 pair;
        uint128 price;
        uint64 timestamp;
        uint16 decimals;
        uint64 round;
    }

    struct CommitteeData {
        CommitteeFeed[] committee_feed;
        bytes32[] proof;
        bool[] flags;
    }

    struct OracleDatum {
        uint64 committee_id;
        bytes32 root;
        uint256[2] sigs;
        CommitteeData committee_data;
    }

    struct OracleProofV2 {
        OracleDatum[] data;
    }

    // Supra pull feed timestamps are in milliseconds, while on-chain checks compare against
    // block.timestamp seconds. Normalize large epoch values to seconds.
    uint256 private constant MILLIS_EPOCH_THRESHOLD = 10_000_000_000;

    // `proof` is raw OracleProofV2 bytes (as returned by Supra pull service).
    function verifyOracleProofV2(bytes calldata proof)
        external
        override
        returns (PriceInfo memory)
    {
        if (proof.length == 0) revert MockBadProof();
        OracleProofV2 memory decoded = abi.decode(proof, (OracleProofV2));

        uint256 totalFeeds = 0;
        for (uint256 i = 0; i < decoded.data.length; i++) {
            totalFeeds += decoded.data[i].committee_data.committee_feed.length;
        }
        if (totalFeeds == 0) revert MockBadProof();

        PriceInfo memory out;
        out.pairs = new uint256[](totalFeeds);
        out.prices = new uint256[](totalFeeds);
        out.timestamp = new uint256[](totalFeeds);
        out.decimal = new uint256[](totalFeeds);
        out.round = new uint256[](totalFeeds);

        uint256 k = 0;
        for (uint256 i = 0; i < decoded.data.length; i++) {
            CommitteeFeed[] memory feeds = decoded.data[i].committee_data.committee_feed;
            for (uint256 j = 0; j < feeds.length; j++) {
                CommitteeFeed memory f = feeds[j];
                out.pairs[k] = uint256(f.pair);
                out.prices[k] = uint256(f.price);
                uint256 ts = uint256(f.timestamp);
                if (ts > MILLIS_EPOCH_THRESHOLD) {
                    ts = ts / 1000;
                }
                out.timestamp[k] = ts;
                out.decimal[k] = uint256(f.decimals);
                out.round[k] = uint256(f.round);
                k++;
            }
        }

        return out;
    }
}
