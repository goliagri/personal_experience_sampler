"""Protocol registry. Importing this package registers the v1 protocols."""

from . import fixed_interval, fixed_times, poisson, stratified  # noqa: F401
from .base import Candidate, get_protocol, get_protocol_or_none, register

__all__ = ["Candidate", "get_protocol", "get_protocol_or_none", "register"]
