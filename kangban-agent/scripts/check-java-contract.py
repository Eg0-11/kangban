"""Validate synthetic responses exported by Java's internal API integration test."""

import argparse
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from pydantic import TypeAdapter

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from kangban_agent import models


def main() -> None:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fixtures",
        type=Path,
        default=repository / "kangban-server/target/internal-agent-contract-fixtures.json",
    )
    args = parser.parse_args()
    if not args.fixtures.is_file():
        parser.error("Run InternalAgentIntegrationTest with the test profile to create fixtures first.")

    contract = json.loads((repository / "docs/contracts/python-agent-v1.schema.json").read_text(encoding="utf-8"))
    fixtures = json.loads(args.fixtures.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(contract)
    if not isinstance(fixtures, list) or not fixtures:
        parser.error("Expected a nonempty list of synthetic callback responses.")
    wire_types = {
        "ToolDefinitionsResponse": list[models.AiToolDefinition],
        "ToolExecutionResponse": models.ToolExecutionResponse,
        "RagSearchResult": models.RagSearchResult,
        "ProposalDecision": models.ProposalDecision,
        "InternalError": models.InternalError,
    }
    for fixture in fixtures:
        schema = {
            "$schema": contract["$schema"],
            "$defs": contract["$defs"],
            "$ref": f"#/$defs/{fixture['definition']}",
        }
        Draft202012Validator(schema).validate(fixture["payload"])
        TypeAdapter(wire_types[fixture["definition"]]).validate_python(fixture["payload"])
    print(f"PASS: {len(fixtures)} Java callback responses conform to the v1 schema and strict Python runtime types.")


if __name__ == "__main__":
    main()
